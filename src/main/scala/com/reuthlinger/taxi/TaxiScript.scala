package com.reuthlinger.taxi
/*
This script shows the code I developed exploratively to get the solution running while using spark-shell

Run:
~/Downloads/spark-2.3.2-bin-hadoop2.7/bin/spark-shell --driver-memory 2G --executor-memory 2G --conf "spark.driver
.memoryOverhead=2G spark.executor.memoryOverhead=2G"

And copy paste the lines below and type Enter to create the object, then type "TaxiScript" to execute all commands
 */

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{corr, expr}

object TaxiScript {

  /**
    * This will create a [[DataFrame]] object from given array of IDs.
    *
    * @param locationIds Array of IDs that shall be used for filter-joining.
    * @return [[DataFrame]] only containing one column "value" that contains the IDs.
    */
  private def createDataFrameForFilteringLocations(locationIds: Array[Int]): DataFrame = {
    val spark: SparkSession = SparkSession.builder().getOrCreate()
    import spark.implicits._
    val filterRdd: RDD[Int] = spark.sparkContext.parallelize(locationIds)
    val filter: DataFrame = spark.createDataset(filterRdd).toDF()
    filter
  }

  val yellowPrefix: String = "yellow"
  val greenPrefix: String = "green"
  val fhvPrefix: String = "fhv"

  val spark: SparkSession = SparkSession.builder().getOrCreate()

  val zoneLookupDataPath: String = "/Users/q370608/git/taxi-experiment/data/lookup/taxi_zone_lookup.csv"

  // get our starting point and target location IDs
  val manhattanLocationIds: Array[Int] = (spark.read.format("csv").option("header", "true")
    .load(zoneLookupDataPath)
    .filter("Borough = 'Manhattan'")
    .select("LocationID")
    .collect()
    .map(row => row(0).toString.toInt))
  val jfkLocationId: Int = (spark
    .read.format("csv").option("header", "true")
    .load(zoneLookupDataPath)
    .filter("Zone = 'JFK Airport'")
    .select("LocationID")
    .collect()(0)(0).toString.toInt
    )

  val dataFolderPath: String = "/Users/q370608/git/taxi-experiment/data/rides/"

  val fhvRidesToJfk: DataFrame = (spark.read.format("csv").option("header", "true")
    .load(s"$dataFolderPath$fhvPrefix*")
    .filter("PUlocationID is not null and DOlocationID is not null")
    .filter(s"DOlocationID = $jfkLocationId")
    .withColumnRenamed("Dispatching_base_num", "RecordingID")
    .withColumnRenamed("Pickup_DateTime", "pickup_datetime")
    .withColumnRenamed("DropOff_datetime", "dropoff_datetime"))

  val greenRidesToJfk = (spark.read.format("csv").option("header", "true")
    .load(s"$dataFolderPath$greenPrefix*")
    .select("VendorID", "lpep_pickup_datetime", "lpep_dropoff_datetime", "PULocationID", "DOLocationID")
    .filter("PUlocationID is not null and DOlocationID is not null")
    .filter(s"DOlocationID = $jfkLocationId")
    .withColumnRenamed("VendorID", "RecordingID")
    .withColumnRenamed("lpep_pickup_datetime", "pickup_datetime")
    .withColumnRenamed("lpep_dropoff_datetime", "dropoff_datetime"))

  val yellowRidesToJfk = (spark.read.format("csv").option("header", "true")
    .load(s"$dataFolderPath$yellowPrefix*")
    .select("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "PULocationID", "DOLocationID")
    .filter("PUlocationID is not null and DOlocationID is not null")
    .filter(s"DOlocationID = $jfkLocationId")
    .withColumnRenamed("VendorID", "RecordingID")
    .withColumnRenamed("tpep_pickup_datetime", "pickup_datetime")
    .withColumnRenamed("tpep_dropoff_datetime", "dropoff_datetime"))

  val allRidesToJfk: DataFrame = fhvRidesToJfk.union(greenRidesToJfk).union(yellowRidesToJfk).cache()

  val manhattanFilter: DataFrame = createDataFrameForFilteringLocations(manhattanLocationIds)

  println("finding all rides from Manhattan to JFK")
  import spark.sqlContext.implicits._
  val ridesFromMhToJfk: DataFrame = (allRidesToJfk
    .join(manhattanFilter, joinExprs = $"PULocationID" === $"value").drop("value").cache())
  ridesFromMhToJfk.show()
  println($"found ${ridesFromMhToJfk.count()} rides matching")

  // now we can aggregate the rides per day
  val ridesPerDay = (ridesFromMhToJfk
    .selectExpr("to_date(coalesce(pickup_datetime, dropoff_datetime)) as ride_date")
    .groupBy("ride_date").count())

  println("loading weather data")
  // now we load the weather data
  val weatherFile: String = "/Users/q370608/git/taxi-experiment/data/weather/NOAA_Central_Park_data.csv"
  val weatherData = (spark.read.format("csv").option("header", "true")
    .load(weatherFile)
    .filter("year(DATE) = 2017")
    .select("DATE", "PRCP", "SNOW", "SNWD", "TMAX", "TMIN", "AWND") // these cols might be useful
    .withColumnRenamed("PRCP", "precipitation"))
  weatherData.show()

  println("creating the joined data set")
  // finally we can join both
  val ridesAndWeather: DataFrame = (ridesPerDay
    .join(weatherData, joinExprs = $"ride_date" === $"DATE")
    .drop("DATE").cache())
  ridesAndWeather.show()
  ridesAndWeather.write.csv("rides-and-weather.csv")

  println("Computing the simple correlation between number of rides and precipitation (per year)")
  val simpleCorr = (ridesAndWeather
    .withColumn("year", expr("year(ride_date)"))
    .groupBy("year")
    .agg(corr("count", "precipitation").as("corr_count_prcp")))
  simpleCorr.show()

  println("computing other potential interesting correlations...")
  val moreCorr = (ridesAndWeather
    .withColumn("year", expr("year(ride_date)"))
    .groupBy("year")
    .agg(
      corr("count", "precipitation").as("corr_count_prcp"),
      corr("count", "SNOW").as("corr_count_SNOW"),
      corr("count", "SNWD").as("corr_count_SNWD"),
      corr("count", "TMAX").as("corr_count_TMAX"),
      corr("count", "TMIN").as("corr_count_TMIN"),
      corr("count", "AWND").as("corr_count_AWND")
    ))
  moreCorr.show()
  val bestPositiveCorrelation = moreCorr.drop("year").collect()(0).toSeq.map(x => x.toString.toDouble).max
  val bestNegaiveCorrelation = moreCorr.drop("year").collect()(0).toSeq.map(x => x.toString.toDouble).min
  println(s"best positive effect correlation (the higher the value the more taxi rides): $bestPositiveCorrelation")
  println(s"best negative effect correlation (the lower the value the more taxi rides): $bestNegaiveCorrelation")
}