package utils

import java.sql.Timestamp
import java.time.format.DateTimeFormatter
import java.util.Calendar

object TimestampUtil extends Serializable {
  val Minutes = 60000.0
  val defaultGranularity = 15

  def timeAgg(inputTime: Timestamp, aggGranularity: Int): Timestamp = {
    val currentTime = inputTime.getTime
    val aggTime = (currentTime / Minutes / aggGranularity).toLong
    new Timestamp(aggTime * Minutes.toInt * aggGranularity)
  }

  def timeAgg(inputTime: Timestamp): Timestamp = {
    val currentTime = inputTime.getTime
    val aggTime = (currentTime / Minutes / defaultGranularity).toLong
    new Timestamp(aggTime * Minutes.toInt * defaultGranularity)
  }

  def addWeek(startTime: Timestamp, week: Int): Timestamp = {
    val time = startTime.getTime
    val calendar = Calendar.getInstance()
    calendar.setTimeInMillis(time)
    calendar.add(Calendar.WEEK_OF_MONTH, week)
    Timestamp.from(calendar.toInstant)
  }

  def quarter2Half(time: String): String = {
    val half = 1000 * 60 * 30
    val timestamp = Timestamp.valueOf(time)
    val halfLong = (timestamp.getTime / half.toDouble).toLong * half
    timestamp.setTime(halfLong)
    // 24小时
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    timestamp.toLocalDateTime.format(formatter)
  }

  def toDateTime(date: String, time: String): String = {
    if (date.length < 8 || time.length < 4) {
      val dateTime = String.format("%s %s", date, time)
      dateTime
    } else {
      val year = date.substring(0, 4)
      val month = date.substring(4, 6)
      val day = date.substring(6, 8)
      val hour = time.substring(0, 2)
      val minute = time.substring(2, 4)
      val second = date.substring(4, 6)
      val dateTime = String.format("%s-%s-%s %s:%s:%s", year, month, day, hour, minute, second)
      dateTime
    }
  }

  /**
    * 只要分配当天的数据
    *
    * @param startTime 当天开始时间
    * @param inputTime 输入时间
    */
  def currentWeekDay(startTime: Timestamp, inputTime: Timestamp): Boolean = {
    val startLocalDate = startTime.toLocalDateTime
    val inputLocalDate = inputTime.toLocalDateTime
    startLocalDate.getDayOfYear == inputLocalDate.getDayOfYear
  }

  def main(args: Array[String]): Unit = {
    val startTime = Timestamp.valueOf("2021-10-22 15:44:44")
    val inputTime = Timestamp.valueOf("2021-10-23 15:44:44")
    println(TimestampUtil.currentWeekDay(startTime, inputTime))
  }
}