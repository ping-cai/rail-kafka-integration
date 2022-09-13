package dataload.base

import java.sql.Timestamp

import dataload.Read
import model.EdgeCongestion
import org.apache.spark.sql.{Dataset, SparkSession}

class OracleSectionCongestionRead(sparkSession: SparkSession) extends Read[Dataset[(EdgeCongestion, Double)]] {
  val sectionCongestionTable = "SCOTT.SECTION_CONGESTION"

  override def read(tableName: String): Dataset[(EdgeCongestion, Double)] = {
    import sparkSession.implicits._
    val sectionCongestionFrame = sparkSession.read.jdbc(url, tableName, prop)
    sectionCongestionFrame.map(sectionCongestion => {
      val time = sectionCongestion.getAs[Timestamp]("time")
      val startId = sectionCongestion.getAs[String]("startId")
      val endId = sectionCongestion.getAs[String]("endId")
      val congestion = sectionCongestion.getAs[java.math.BigDecimal]("utilizationRate").doubleValue()
      (EdgeCongestion(time, startId, endId), congestion)
    })
  }


}

object OracleSectionCongestionRead {
  def main(args: Array[String]): Unit = {
    val sparkSession = SparkSession.builder().master("local[*]").getOrCreate()
    val congestionRead = new OracleSectionCongestionRead(sparkSession)
    congestionRead.read(congestionRead.sectionCongestionTable)
  }
}
