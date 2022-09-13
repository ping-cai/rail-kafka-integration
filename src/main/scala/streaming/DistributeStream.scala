package streaming

import java.sql.Timestamp
import java.time.LocalDateTime

import batch.StaticDistribute
import conf.DynamicConf
import dataload.{Load, ODLoadByOracle}
import dataload.base.OracleChongQingLoad
import flowdistribute.OdWithTime
import model.ODWithFlow
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.from_json
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.{DataTypes, StructType}
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.collection.mutable
import scala.util.parsing.json.JSON

/**
  *
  * @param sparkSession spark会话
  */
class DistributeStream(sparkSession: SparkSession) extends StreamCompute {
  // 读入配置信息
  val dynamicParamConf: DynamicConf.type = DynamicConf
  // 定义结构体
  val distributionRequest: StructType = new StructType()
    .add("startTime", DataTypes.TimestampType)
  val distributionTopic = "od-distribution"

  import sparkSession.implicits._

  private def readDistributionRequest(): DataFrame = {
    // 设置日志展示级别
    sparkSession.sparkContext.setLogLevel("WARN")
    // 读取kakfa的流式处理操作
    val kafkaStreaming = sparkSession.readStream
      .format("kafka")
      // kafka服务器地址
      .option("kafka.bootstrap.servers", dynamicParamConf.brokers)
      // kafka topic信息
      .option("subscribe", distributionTopic)
      .option("startingOffsets", "latest")
      .option("enable.auto.commit", "false")
      .option("auto.commit.interval.ms", "5000")
      .load()
    // 转换kafka 二进制流为string类型
    val kafkaData = kafkaStreaming.selectExpr("CAST(key AS STRING)", "CAST (value AS STRING) as json")
      .as[(String, String)]
      //      过滤json
      .filter(x => JSON.parseFull(x._2).isDefined)
      //      对应schema信息，列别名取为od
      .select(from_json($"json", schema = distributionRequest).alias("odDistribution"))
      .select("odDistribution.startTime")
    kafkaData
  }

  override def compute(): Unit = {

    val requestFrame = readDistributionRequest()
    // 打印数据类型
    requestFrame.printSchema()

    val oDLoadByOracle = sparkSession.sparkContext.broadcast(new ODLoadByOracle(sparkSession))
    val staticDistribute = sparkSession.sparkContext.broadcast(new StaticDistribute(sparkSession))
    val transferMap = getTransferMap
    val transferMapBroadcast = sparkSession.sparkContext.broadcast(transferMap)
    // 输出数据的类型和方式
    requestFrame.writeStream.foreachBatch((batchFrame: DataFrame, batchId: Long) => {
      // 把三台数据合成一块
      batchFrame.collect().foreach(x => {

        // 获取数据
        val startTime = x.getTimestamp(0)
        val odRdd = oDLoadByOracle.value.getOdRdd(startTime)
        val odTransferRdd = odRdd.map(od => {
          val inId = od.getInId
          val outId = od.getOutId
          val systemInId = transferSystemId(transferMapBroadcast, inId)
          val systemOutId = transferSystemId(transferMapBroadcast, outId)
          od.setInId(systemInId)
          od.setOutId(systemOutId)
          od
        })
        val realOd = odTransferRdd.filter(x => !x.getInId.contains("not") && !x.getOutId.contains("not"))
        saveFailOd(startTime, odTransferRdd)
        staticDistribute.value.startup(realOd, startTime)
      })
    }).outputMode(OutputMode.Append()).start().awaitTermination()
  }

  private def saveFailOd(startTime: Timestamp, odTransferRdd: RDD[OdWithTime]) = {
    val failOd = odTransferRdd.filter(x => x.getInId.contains("not") || x.getOutId.contains("not"))
    val failOdFrame = failOd.map(od => {
      val inId = od.getInId
      val outId = od.getOutId
      ODWithFlow(inId, outId, "", 1)
    }).toDS()
    val localDateTime: LocalDateTime = startTime.toLocalDateTime
    failOdFrame.write.jdbc(Load.url, s"KALMAN_OD_FAIL_${localDateTime.getDayOfYear}", Load.prop)
  }

  private def transferSystemId(transferMapBroadcast: Broadcast[mutable.Map[String, String]], id: String): String = {
    val maybeId = transferMapBroadcast.value.get(id)
    val systemId =
      if (maybeId.isDefined) {
        maybeId.get
      } else {
        s"not-$id"
      }
    systemId
  }

  def getTransferMap: mutable.Map[String, String] = {
    val chongQingLoad = new OracleChongQingLoad()
    val afcTransferFrame = chongQingLoad.load()
    val afcTransferIdMap: mutable.Map[String, String] = mutable.HashMap[String, String]()
    afcTransferFrame.rdd.collect.map(
      x => {
        val afcId = x.getString(0)
        val stationId = x.getDecimal(1).intValue().toString
        afcTransferIdMap.put(afcId, stationId)
      }
    )
    afcTransferIdMap
  }
}

/**
  * 1.监听kafka数据，主要是时间，为了下一步读取数据库
  * 2.设置数据库的配置信息，URL,表名,prop
  * 3.读取数据库数据-> dataFrame
  * 4.先计算K短路结果，按照时间排序的结果 -> (路径,时间费用)
  * 5.算路径对应的静态费用,换乘一次我定义24分钟->(路径,静态费用) 静态费用包含区间运行时间，换乘费用，列车停站费用
  * 6.进行logit概率模型进行流量分配 -> (START,END,路径,人流) 先得到区间运行时刻表(区间ID->旅行时间)
  * 7.进行区间分配 每个区间+人数+通过时间
  * 8.进行换乘分配，找到路径的换乘点,1下行，2上行，0是换乘点，上进上出
  * 9.进行车站分配，包含换乘车站，如果有换乘，算换乘+起始站点和终止站点，没有换乘就算起始站点和终止站点
  * 10.结果入库
  */
object DistributeStream {
  def main(args: Array[String]): Unit = {
    //    SparkSession.builder().master("local[*]").appName("DistributeStream").getOrCreate()
    val sparkSession = SparkSession.builder().appName("DistributeStream").getOrCreate()
    val distributeStream = new DistributeStream(sparkSession)
    distributeStream.compute()
  }
}
