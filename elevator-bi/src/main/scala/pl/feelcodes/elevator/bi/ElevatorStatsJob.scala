package pl.feelcodes.elevator.bi

import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.slf4j.LoggerFactory
import pl.feelcodes.elevator.bi.config.BiConfig
import pl.feelcodes.elevator.bi.kafka.ElevatorStateSchema
import pl.feelcodes.elevator.bi.sink.ParquetSink

/** Spark batch BI job: re-scan the elevator-state topic + order_status each cycle,
  * join to one row per elevator, overwrite elevators.parquet. The api reads that
  * file via DuckDB — no analytics DB. Loops to stay fresh; the fleet is tiny so a
  * full re-scan is cheap.
  */
object ElevatorStatsJob {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val cfg = BiConfig.fromEnv()
    val spark = SparkSession.builder().appName("elevator-stats").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    try run(spark, cfg)
    finally spark.stop()
  }

  @volatile private var running = true

  def run(spark: SparkSession, cfg: BiConfig): Unit = {
    if (cfg.runOnce) {
      log.info(s"stats: single pass -> ${cfg.parquetPath} (run-once; external scheduler owns cadence)")
      refreshOnce(spark, cfg)
      return
    }

    sys.addShutdownHook { running = false }
    log.info(s"stats: refreshing ${cfg.parquetPath} every ${cfg.intervalSeconds}s")
    while (running) {
      refreshOnce(spark, cfg)
      sleepInterruptibly(cfg.intervalSeconds)
    }
  }

  def refreshOnce(spark: SparkSession, cfg: BiConfig): Unit = {
    val mileage = ElevatorStats.mileage(readStateEvents(spark, cfg), spark)
    val served  = OrdersServed.tally(readOrderStatus(spark, cfg))
    val stats   = ElevatorStats.join(mileage, served, spark)
    ParquetSink.write(stats, cfg)
    log.info(s"stats: wrote ${stats.count()} elevator rows to ${cfg.parquetPath}")
  }

  private def readStateEvents(spark: SparkSession, cfg: BiConfig): Dataset[StateEvent] = {
    import spark.implicits._
    spark.read
      .format("kafka")
      .option("kafka.bootstrap.servers", cfg.kafkaBootstrap)
      .option("subscribe", cfg.stateTopic)
      .option("startingOffsets", "earliest")
      .option("endingOffsets", "latest")
      .load()
      .select(
        org.apache.spark.sql.functions
          .from_json(col("value").cast("string"), ElevatorStateSchema.schema)
          .as("s"),
        col("offset").as("offset"))
      .select(col("s.elevatorName").as("elevatorName"), col("s.floor").as("floor"), col("offset"))
      .where(col("elevatorName").isNotNull.and(col("floor").isNotNull))
      .as[StateEvent]
  }

  private def readOrderStatus(spark: SparkSession, cfg: BiConfig): DataFrame =
    spark.read
      .format("jdbc")
      .option("url", cfg.sourceJdbcUrl)
      .option("dbtable", cfg.orderStatusTable)
      .option("user", cfg.jdbcUser)
      .option("password", cfg.jdbcPassword)
      .option("driver", "org.postgresql.Driver")
      .load()

  private def sleepInterruptibly(seconds: Int): Unit = {
    var left = seconds
    while (running && left > 0) {
      Thread.sleep(1000L)
      left -= 1
    }
  }
}
