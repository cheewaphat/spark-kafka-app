package com.markgrover.spark.kafka

import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.kafka.v09.KafkaUtils

object DirectKafkaAverageHouseholdIncome {
  def main(args: Array[String]) {
    import org.apache.spark.SparkConf
    import org.apache.spark.streaming._

    val conf = new SparkConf().setAppName(this.getClass.toString)
    val ssc = new StreamingContext(conf, Seconds(1))
    val hdfsPath = "/user/hive/warehouse/income"
    val kafkaParams: Map[String, String] = Map("auto.offset.reset" -> "earliest",
      "bootstrap.servers" -> "mgrover-st-1.vpc.cloudera.com")

    val incomeCsv = KafkaUtils.createDirectStream[String, String](ssc, kafkaParams, Set("income"))
    //val incomeCsv = ssc.textFileStream(hdfsPath)

    // Format of the data is
    //GEO.id,GEO.id2,GEO.display-label,VD01
    //Id,Id2,Geography,Median family income in 1999
    //8600000US998HH,998HH,"998HH 5-Digit ZCTA, 998 3-Digit ZCTA",0
    val areaIncomeStream = parse(incomeCsv)

    // First element of the tuple in DStream are total incomes, second is total number of zip codes
    // in that geographic area, for which the income is shown
    val runningTotals = areaIncomeStream.mapValues(x => (x, 1))
      .reduceByKey((x, y) => (x._1 + y._1, x._2 + y._2)).mapValues(divide)

    runningTotals.print(20)

    ssc.start()
    ssc.awaitTermination()
  }

  def divide(xy: (Int, Int)): Double = {
    if (xy._2 == 0) {
      0
    } else {
      xy._1/xy._2
    }

  }

  def parse(incomeCsv: DStream[(String, String)]): DStream[(String, Int)] = {
    val builder = StringBuilder.newBuilder
    val parsedCsv: DStream[List[String]] = incomeCsv.map(entry => {
      val x = entry._2
      var result = List[String]()
      var withinQuotes = false
      x.foreach(c => {
        if (c.equals(',') && !withinQuotes) {
          result = result :+ builder.toString
          builder.clear()
        } else if (c.equals('\"')) {
          builder.append(c)
          withinQuotes = !withinQuotes
        } else {
          builder.append(c)
        }
      })
      result :+ builder.toString
    })
    // 2nd element (index 1) is zip code, last element (index 3) is income
    // We take the first 3 digits of zip code and find average income in that geographic area
    parsedCsv.map(record => (record(1).substring(0, 3), record(3).toInt))

  }
}
