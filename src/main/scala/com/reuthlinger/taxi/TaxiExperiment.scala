package com.reuthlinger.taxi

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{corr, expr}

object TaxiExperiment {

  val yellowPrefix: String = "yellow"
  val greenPrefix: String = "green"
  val fhvPrefix: String = "fhv"

  case class Config(
                     yearToProcess: Int = 2017,
                     ridesDataPath: String = "data/rides/",
                     lookupDataFile: String = "data/lookup/taxi_zone_lookup.csv",
                     weatherDataFile: String = "data/weather/NOAA_Central_Park_data.csv"
                   )

  /**
    * Extract the JVM call parameters and parse them into a [[Config]] object.
    *
    * @param args JVM call arguments.
    * @return Defined config object.
    */
  def parseArgs(args: Array[String]): Config = {
    val parser = new scopt.OptionParser[Config]("scopt") {
      head("TaxiExperiment")

      opt[Int]("year-to-process")
        .optional
        .action((x, c) => c.copy(yearToProcess = x))
        .text("the year (yyyy) that should be processed, needs to fit to the data preselected outside this program")

      opt[String]("rides-relative-path")
        .optional
        .action((x, c) => c.copy(ridesDataPath = x))
        .text("(relative) path to read taxi rides data from")

      opt[String]("lookup-file-relative-path")
        .optional
        .action((x, c) => c.copy(lookupDataFile = x))
        .text("(relative) path to read taxi rides zone lookup data from")

      opt[String]("weather-file-relative-path")
        .optional
        .action((x, c) => c.copy(weatherDataFile = x))
        .text("(relative) path to read weather data from")
    }

    val parsedConfig = parser.parse(args, Config()) match {
      case Some(config) => config
      case None =>
        println("Wrong arguments specified. Please see the usage for more details.")
        sys.exit(1)
    }
    parsedConfig
  }

  /**
    * The CSV file referenced by parameter "config.lookupDataFile" contains exact one zone ID for the JFK Airport.
    *
    * @param config The Spark job's config.
    * @return Location ID Integer for JFK Airport.
    */
  private def lookupJfkLocationId(config: Config): Int = {
    SparkSession.builder().getOrCreate()
      .read.format("csv").option("header", "true")
      .load(config.lookupDataFile)
      .filter("Zone = 'JFK Airport'") // this defines the exact one entry
      .select("LocationID")
      .collect()(0)(0) // get the one Integer from the result array
      .toString.toInt
  }

  /**
    * The CSV file referenced by parameter "config.lookupDataFile" contains an array of entries that contain IDs for
    * locations in Manhattan.
    *
    * @param config The Spark job's config.
    * @return Array of location ID Integers for Manhattan district.
    */
  private def lookupManhattanLocationIDs(config: Config): Array[Int] = {
    SparkSession.builder().getOrCreate()
      .read.format("csv").option("header", "true")
      .load(config.lookupDataFile)
      .filter("Borough = 'Manhattan'") // this defines all locations for Manhattan
      .select("LocationID")
      .collect()
      .map(row => row.get(0).toString.toInt)
  }

  /**
    * Load taxi ride data from the three different sources (FHV, green and yellow cabs).
    *
    * @param config        Spark job's config.
    * @param jfkLocationId The location ID for JFK Airport.
    * @return DataFrame containing the filtered rides that went to JFK.
    */
  private def loadAllRidesToJfk(config: Config, jfkLocationId: Int): DataFrame = {
    val spark: SparkSession = SparkSession.builder().getOrCreate()
    val fhvRidesToJfk: DataFrame = spark.read.format("csv").option("header", "true")
      .load(s"${config.ridesDataPath}$fhvPrefix*")
      .filter("PUlocationID is not null and DOlocationID is not null")
      .filter(s"DOlocationID = $jfkLocationId")
      .withColumnRenamed("Dispatching_base_num", "RecordingID")
      .withColumnRenamed("Pickup_DateTime", "pickup_datetime")
      .withColumnRenamed("DropOff_datetime", "dropoff_datetime")

    val greenRidesToJfk = spark.read.format("csv").option("header", "true")
      .load(s"${config.ridesDataPath}$greenPrefix*")
      .select("VendorID", "lpep_pickup_datetime", "lpep_dropoff_datetime", "PULocationID", "DOLocationID")
      .filter("PUlocationID is not null and DOlocationID is not null")
      .filter(s"DOlocationID = $jfkLocationId")
      .withColumnRenamed("VendorID", "RecordingID")
      .withColumnRenamed("lpep_pickup_datetime", "pickup_datetime")
      .withColumnRenamed("lpep_dropoff_datetime", "dropoff_datetime")

    val yellowRidesToJfk = spark.read.format("csv").option("header", "true")
      .load(s"${config.ridesDataPath}$yellowPrefix*")
      .select("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "PULocationID", "DOLocationID")
      .filter("PUlocationID is not null and DOlocationID is not null")
      .filter(s"DOlocationID = $jfkLocationId")
      .withColumnRenamed("VendorID", "RecordingID")
      .withColumnRenamed("tpep_pickup_datetime", "pickup_datetime")
      .withColumnRenamed("tpep_dropoff_datetime", "dropoff_datetime")

    val allRidesToJfk: DataFrame = fhvRidesToJfk.union(greenRidesToJfk).union(yellowRidesToJfk)
    allRidesToJfk
  }

  /**
    * This will load all necessary files to get the information how many rides per day went from Manhattan district
    * to JFK airport.
    *
    * @param config Spark job's config.
    * @return DataFrame containing the date (yyyy-mm-dd) and count of rides taken that day on this route.
    */
  private def loadRidesPerDay(config: Config): DataFrame = {
    val spark: SparkSession = SparkSession.builder().getOrCreate()

    // this loads an additional file from the taxi data set from internet for looking up the IDs of locations
    // where someone entered or dropped off
    val manhattanLocationIds: Array[Int] = lookupManhattanLocationIDs(config)
    val jfkLocationId: Int = lookupJfkLocationId(config)

    // this will pre-filter all rides for just the ones ending at JFK (as this is simple and performant)
    val allRidesToJfk: DataFrame = loadAllRidesToJfk(config, jfkLocationId).cache()

    // now we need to select the drives that were starting at Manhattan
    val manhattanFilter: DataFrame = createDataFrameForFilteringLocations(manhattanLocationIds)
    import spark.sqlContext.implicits._
    // here we join the IDs of Manhattan to the DF and by that get a simple way to filter for starting at MH
    val ridesFromMhToJfk: DataFrame = allRidesToJfk
      .join(manhattanFilter, joinExprs = $"PULocationID" === $"value").drop("value").cache()

    // now we can aggregate the rides per day
    val ridesPerDay: DataFrame = ridesFromMhToJfk
      .selectExpr("to_date(coalesce(pickup_datetime, dropoff_datetime)) as ride_date")
      .groupBy("ride_date").count()
    ridesPerDay
  }

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

  /**
    * Load Central Park's weather data for a specific year.
    *
    * @param config Spark job's config.
    * @return DataFrame containing some useful information per day.
    */
  private def loadWeatherData(config: Config): DataFrame = {
    SparkSession.builder().getOrCreate().read.format("csv").option("header", "true")
      .load(config.weatherDataFile)
      .filter(s"year(DATE) = ${config.yearToProcess}")
      .select("DATE", "PRCP", "SNOW", "SNWD", "TMAX", "TMIN", "AWND") // these cols might be useful
      .withColumnRenamed("PRCP", "precipitation")
  }

  /**
    * Default main method. Contains argument parser, see [[Config]].
    *
    * @param args List of arguments.
    */
  def main(args: Array[String]): Unit = {
    val config: Config = parseArgs(args)
    val spark: SparkSession = SparkSession.builder().getOrCreate()

    val ridesPerDay: DataFrame = loadRidesPerDay(config)
    val weatherData: DataFrame = loadWeatherData(config)

    // to create the data set for running correlation on, we join both (rides and weather)
    import spark.sqlContext.implicits._
    val ridesAndWeather: DataFrame = ridesPerDay.join(weatherData, joinExprs = $"ride_date" === $"DATE").cache()
    // Storing data here for later viz
    ridesAndWeather.coalesce(1).write.format("csv").option("header", "true").save("rides-and-weather")

    // now we calculate the correlations
    val correlations = ridesAndWeather
      .withColumn("year", expr("year(ride_date)"))
      .groupBy("year")
      .agg(
        corr("count", "precipitation").as("corr_count_prcp"),
        corr("count", "SNOW").as("corr_count_SNOW"),
        corr("count", "SNWD").as("corr_count_SNWD"),
        corr("count", "TMAX").as("corr_count_TMAX"),
        corr("count", "TMIN").as("corr_count_TMIN"),
        corr("count", "AWND").as("corr_count_AWND")
      )
    correlations.show()
    correlations.coalesce(1).write.format("csv").option("header", "true").save("results")
    val bestPositiveCorrelation = correlations.drop("year").collect()(0).toSeq.map(x => x.toString.toDouble).max
    val bestNegaiveCorrelation = correlations.drop("year").collect()(0).toSeq.map(x => x.toString.toDouble).min
    println(s"best positive effect correlation (the higher the value the more taxi rides): $bestPositiveCorrelation")
    println(s"best negative effect correlation (the lower the value the more taxi rides): $bestNegaiveCorrelation")
  }
}
