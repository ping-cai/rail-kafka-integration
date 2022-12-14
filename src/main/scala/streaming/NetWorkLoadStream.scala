package streaming

import java.sql.Timestamp
import java.util.Properties

import calculate.{BaseCalculate, Logit}
import conf.DynamicConf
import control.Control
import costcompute._
import dataload.{BaseDataLoad, Load}
import distribution.DistributionResult
import domain.Section
import flowdistribute.OdWithTime
import kspcalculation.Path
import model._
import model.dto.{SectionLoadDTO, StationLoadDTO}
import org.apache.spark.sql._
import org.apache.spark.sql.functions.{max, min, sum, window}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.{DataTypes, StructType}
import org.slf4j.{Logger, LoggerFactory}
import utils.{TimeKey, TravelTimeHandle}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

class NetWorkLoadStream(sparkSession: SparkSession) extends StreamCompute {
  private val log: Logger = LoggerFactory.getLogger(this.getClass)
  val dynamicParamConf: DynamicConf.type = DynamicConf
  val odListSchema: StructType = new StructType()
    .add("odList", DataTypes.createArrayType(DataTypes.StringType))
  val oWithOutDSchema: StructType = new StructType()
    .add("inId", DataTypes.StringType)
    .add("passengers", DataTypes.DoubleType)
    .add("inTime", DataTypes.StringType)
  val odSchema: StructType = new StructType()
    .add("outId", DataTypes.StringType)
    .add("oWithOutD", oWithOutDSchema)
  val url: String = dynamicParamConf.localhostUrl
  val prop: Properties = Load.prop
  //  网络负载预测系统topic，network-load
  private val networkLoadTopic = dynamicParamConf.topics(2)
  //    导入隐式转换
  import sparkSession.implicits._

  /**
    * 网络负载预测系统topic，network-load-1
    * 1.流式数据的读取可能存在多个
    * 2.流式数据的写出可能会有多个
    */
  override def compute(): Unit = {
    val readOdStream = new ReadOdStream(sparkSession)
    val kafkaData: DataFrame = readOdStream.readStream("NetWorkLoadStream")
    //      //      得到json数据的子对象值
    //      .select("od.inId", "od.outId", "od.inTime", "od.outTime", "od.passengers")
    val baseDataLoad = new BaseDataLoad
    //    创建列车运行时刻表图
    val sectionTravelGraph = baseDataLoad.getSectionTravelGraph
    val sc = sparkSession.sparkContext
    val trainOperationSectionBroad = sc.broadcast(baseDataLoad.getTrainOperationSection.getSectionTrainMap)
    //    获取所有区间在某个时间通过列车的数量
    val trainPassSectionMap = mutable.HashMap[Section, Map[TimeKey, mutable.Buffer[TravelTime]]]()
    sectionTravelGraph.getSectionListMap.asScala.foreach(x => {
      val section = x._1
      val timeKeyContainTravelTimeMap: Map[TimeKey, mutable.Buffer[TravelTime]] = x._2.asScala.groupBy(x => {
        TravelTimeHandle.getTimeKey(x, dynamicParamConf.timeInterval)
      })
      trainPassSectionMap.put(section, timeKeyContainTravelTimeMap)
    })
    val trainPassSectionMapBroad = sc.broadcast(trainPassSectionMap)
    val afcTransferIdMapBroad = sc.broadcast(baseDataLoad.getAfcTransferIdMap)
    //    可计算K短路
    val baseCalculate = new BaseCalculate(baseDataLoad, sectionTravelGraph)
    val baseCalculateBroad = sc.broadcast(baseCalculate)
    val distributionResult = sc.broadcast(Control.createLRUDistributionResult())
    //    val distributionResult = sc.broadcast(Control.createDistributionResult())
    val encoder = Encoders.kryo[(OdWithTime, Try[java.util.List[Path]])]
    val minGeneralizedCost = sc.broadcast(new MinGeneralizedCost())
    val odWithPathTry = kafkaData
      .repartition(80)
      .filter(od => {
        val afcInId = od.getString(0)
        val afcOutId = od.getString(1)
        afcTransferIdMapBroad.value.containsKey(afcInId) && afcTransferIdMapBroad.value.containsKey(afcOutId)
      }).map(od => {
      val afcInId = od.getString(0)
      val afcOutId = od.getString(1)
      val inTime = od.getString(2)
      val passengers = od.getDouble(3)
      //        val oDWithTimeModel = ODWithTimeModel(inId, outId, inTime, outTime)
      val afcTransferIdMap = afcTransferIdMapBroad.value
      val inId = afcTransferIdMap.get(afcInId)
      val outId = afcTransferIdMap.get(afcOutId)
      val odWithTime = new OdWithTime(inId, outId, inTime, passengers)
      //        得到K短路
      val startTime = System.currentTimeMillis()
      val pathListTry = Try(baseCalculateBroad.value.getLegalPathList(dynamicParamConf.pathNum, odWithTime))
      log.info("pathListTry:time:{}ms", System.currentTimeMillis() - startTime)
      if (pathListTry.isFailure) {
        log.error("pathSearchException!The od is {}", odWithTime)
      }
      (odWithTime, pathListTry)
    })(encoder)
    val timeLoadTry = odWithPathTry.filter(x => x._2.isSuccess).map(
      odWithPath => {

        val odWithTime = odWithPath._1
        val pathList = odWithPath._2.get
        //        得到静态费用
        val staticCost = baseCalculateBroad.value.getStaticCost(pathList)
        //        得到最小费用
        val minCost = minGeneralizedCost.value.compose(staticCost)
        //        创建临时集合
        val tempResult = Control.createDistributionResult()
        //            动态费用计算
        var startTime = System.currentTimeMillis()
        val dynamicCost = baseCalculateBroad.value.dynamicCalculate(odWithTime, staticCost, minCost, pathList,
          distributionResult.value.getTimeIntervalTraffic, dynamicParamConf.timeInterval)
        log.info("dynamicCost:time:{}ms", System.currentTimeMillis() - startTime)
        //            logit模型分配
        val logitResult = Logit.logit(dynamicCost, minCost, odWithTime.getPassengers)
        //            动态分配
        startTime = System.currentTimeMillis()
        baseCalculateBroad.value.distribute(logitResult, odWithTime.getInTime, dynamicParamConf.timeInterval,
          distributionResult.value, tempResult)
        log.info("baseCalculateBroad:distribute:time:{}ms", System.currentTimeMillis() - startTime)
        val timeWithStationLoads: List[TimeWithStationLoad] = getStationLoad(tempResult).toList
        val timeWithSectionLoads = Try(getSectionLoad(distributionResult.value, tempResult, trainPassSectionMapBroad.value,
          trainOperationSectionBroad.value).toList)
        //    2.Driver端定义实体，实体用HashMap来表示
        (timeWithStationLoads, timeWithSectionLoads)
        //        假如有两个OD，那么这两个OD可能产生的netLoadInfo可能会有重合的部分，这个时候又需要聚合
      }
    )
    val timeLoadFrame = timeLoadTry.filter(x => x._2.isSuccess).map(x => (x._1, x._2.get))
    timeLoadFrame.createOrReplaceTempView("timeLoad")
    val stationDelay = DynamicConf.netWorkLoadDelayThreshold
    val timeWithStationFrame = timeLoadFrame.flatMap(x => x._1)
      .select($"timeKeyModel.startTime".cast(DataTypes.TimestampType).as("startTime"), $"stationLoad")
      .withWatermark("startTime", stationDelay)
    val sectionDelay = DynamicConf.netWorkLoadDelayThreshold
    val timeWithSectionFrame = timeLoadFrame.flatMap(x => x._2)
      .select($"timeKeyModel.startTime".cast(DataTypes.TimestampType).as("startTime"), $"sectionLoad")
      .withWatermark("startTime", sectionDelay)
    //  如何将stationFrame聚合为一个List集合呢？
    val windowDuration = DynamicConf.windowDuration
    val slideDuration = DynamicConf.slideDuration
    val querySection = timeWithSectionFrame.writeStream
      .foreachBatch((batch: DataFrame, _: Long) => {
        val tryQuerySectionBatch = Try(querySectionBatch(windowDuration, slideDuration, batch))
      })
      .outputMode(OutputMode.Append()).start()

    val queryStation = timeWithStationFrame.writeStream
      .foreachBatch((batch: DataFrame, _: Long) => {
        val tryQueryStationBatch = Try(queryStationBatch(windowDuration, slideDuration, batch))
      })
      .outputMode(OutputMode.Append()).start()
    querySection.awaitTermination()
    queryStation.awaitTermination()
  }

  private def querySectionBatch(windowDuration: String, slideDuration: String, batch: DataFrame): Unit = {

    //      这里开始聚合
    val sectionWindow = batch.groupBy(window($"startTime", windowDuration, slideDuration),
      $"sectionLoad.startId", $"sectionLoad.endId", $"sectionLoad.trainNum")
      .agg(sum("sectionLoad.volume") as "volume", max("sectionLoad.passengers") as "passengers", sum("sectionLoad.utilizationRate") as "utilizationRate")
      .select("window.start", "startId", "endId", "volume", "trainNum", "passengers", "utilizationRate")
      .withColumnRenamed("start", "startTime")
    val startTime = System.currentTimeMillis()
    sectionWindow.write.mode(SaveMode.Append).jdbc(url, "SECTION_LOAD", prop)
    log.info("sectionWindow.write.mode(SaveMode.Append):time:{}ms", System.currentTimeMillis() - startTime)

  }

  private def queryStationBatch(windowDuration: String, slideDuration: String, batch: DataFrame): Unit = {

    /*
         1.将两个流式数据分别聚合
         2.在driver端定义对象实体
         3.遍历两个流式数据集，对数据集进行遍历后得到该对象的值，这里牵涉到网络传输通信
         4.将该对象交给spark,通过spark操作kafkaAPI发送
      */
    val stationWindow = batch.groupBy(window($"startTime", windowDuration, slideDuration),
      $"stationLoad.stationId")
      .agg(sum("stationLoad.avgVolume") as "avgVolume", sum("stationLoad.passengers") as "passengers", sum("stationLoad.inVolume") as "inVolume", sum("stationLoad.outVolume") as "outVolume", sum("stationLoad.crowdingRate") as "crowdingRate")
      .select("window.start", "stationId", "passengers", "avgVolume", "inVolume", "outVolume", "crowdingRate")
      .withColumnRenamed("start", "startTime")
    val startTime = System.currentTimeMillis()
    stationWindow.write.mode(SaveMode.Append).jdbc(url, "STATION_LOAD", prop)
    log.info("stationWindow.write.mode(SaveMode.Append):time:{}ms", System.currentTimeMillis() - startTime)
    stationWindow.select("startTime").agg(min($"startTime" cast DataTypes.LongType) as "startTime")
      .select($"startTime" cast DataTypes.TimestampType).toJSON
      .write
      .format("kafka")
      .option("kafka.bootstrap.servers", dynamicParamConf.brokers)
      .option("topic", networkLoadTopic)
      .save()
  }

  /**
    * 如何计算车的数量？通过区间->得到时间,在计算多少时间在timeKey内，在特定时间段内，通过的车数量
    *
    * @param tempResult 分配的暂时结果
    * @return
    */
  private def getSectionLoad(result: DistributionResult, tempResult: DistributionResult, trainPassSectionMap: mutable.HashMap[Section, Map[TimeKey, mutable.Buffer[TravelTime]]],
                             trainOperationSection: java.util.Map[Section, Train]) = {
    val finalResult = result.getTimeIntervalTraffic.getTimeSectionTraffic
    val sectionTraffic = tempResult.getTimeIntervalTraffic.getTimeSectionTraffic
    val timeWithSectionLoads = sectionTraffic.asScala.flatMap(timeSection => {
      val timeKey = timeSection._1
      val finalSectionMap = finalResult.get(timeKey)
      val timeWithOutDate = TimeKey.getOnlyTime(timeKey)
      val timeSectionMap = timeSection._2.asScala.map(sectionFlow => {
        val section = sectionFlow._1
        val passengersValue = finalSectionMap.get(section)
        val passengers: Double = if (passengersValue.isNaN) {
          0.0
        } else {
          passengersValue
        }
        val value = sectionFlow._2
        val flow: Double = if (value.isNaN) {
          0.0
        } else {
          value
        }
        val travelTimes = trainPassSectionMap(section)
        val arrivalNum = travelTimes(timeWithOutDate)
        val trainNum = arrivalNum.size
        val train = trainOperationSection.get(section)
        val utilizationRate = NetWorkLoadStream.computeUtilizationRate(flow, train, trainNum)
        val timeWithSectionLoad = TimeWithSectionLoad(TimeKeyModel(timeKey.getStartTime, timeKey.getEndTime),
          SectionLoadDTO(section.getInId, section.getOutId, flow, trainNum, passengers, utilizationRate))
        timeWithSectionLoad
      })
      timeSectionMap
    })
    timeWithSectionLoads
  }

  private def getStationLoad(tempResult: DistributionResult) = {
    val timeWithStationLoads = tempResult.getTimeIntervalStationFlow.getTimeStationTraffic.asScala.flatMap(timeStation => {
      val timeKey = timeStation._1
      val stationFlowMap = timeStation._2
      val stationLoads = stationFlowMap.asScala.map(stationFlow => {
        val station = stationFlow._1
        val value = stationFlow._2
        val flow: Double = if (value.isNaN) {
          0.0
        } else {
          value
        }
        if ("in".equals(station.getType)) {
          val stationLoad = StationLoadDTO(station.getStationId, flow, flow, 0, flow, flow / 1000)
          TimeWithStationLoad(TimeKeyModel(timeKey.getStartTime, timeKey.getEndTime), stationLoad)
        } else {
          val stationLoad = StationLoadDTO(station.getStationId, flow, 0, flow, flow, flow / 1000)
          TimeWithStationLoad(TimeKeyModel(timeKey.getStartTime, timeKey.getEndTime), stationLoad)
        }
      })
      stationLoads
    })
    timeWithStationLoads
  }
}

object NetWorkLoadStream {
  def main(args: Array[String]): Unit = {
    val sparkSession = SparkSession.builder().appName("NetWorkLoadStream").getOrCreate()
    val odStreamingToOracleCompute = new NetWorkLoadStream(sparkSession)
    odStreamingToOracleCompute.compute()
  }

  def timeStampToString(timestamp: Timestamp): String = {
    import java.text.SimpleDateFormat
    val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    val standardDate = sdf.format(timestamp)
    standardDate
  }

  def computeUtilizationRate(passengers: Double, train: Train, trainNum: Int): Double = {
    passengers / train.getMaxCapacity * trainNum
  }
}
