package pl.feelcodes.elevator.bi

import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.slf4j.LoggerFactory
import pl.feelcodes.elevator.bi.config.BiConfig
import pl.feelcodes.elevator.bi.kafka.{CallSchema, ElevatorStateSchema}
import pl.feelcodes.elevator.bi.sink.ParquetSink

/** Spark batch BI job: re-scan the elevator-state topic + the order_status / call_status
  * read models each cycle and overwrite one detailed fact table ([[FactTable]]). The api
  * reads that single file via DuckDB and computes every stat as a view — no analytics DB.
  * Loops to stay fresh; the model is small so a full re-scan is cheap.
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
      log.info(s"bi: single pass -> ${cfg.factPath} (run-once; external scheduler owns cadence)")
      refreshOnce(spark, cfg)
      return
    }

    sys.addShutdownHook { running = false }
    log.info(s"bi: refreshing ${cfg.factPath} every ${cfg.intervalSeconds}s")
    while (running) {
      refreshOnce(spark, cfg)
      sleepInterruptibly(cfg.intervalSeconds)
    }
  }

  def refreshOnce(spark: SparkSession, cfg: BiConfig): Unit = {
    val orderStatus = readOrderStatus(spark, cfg)
    val callStatus  = readCallStatus(spark, cfg)
    val calls       = readCalls(spark, cfg)
    val mileage     = ElevatorStats.mileage(readStateEvents(spark, cfg), spark)

    val facts = FactTable.build(mileage, orderStatus, callStatus, calls, spark).cache()
    try {
      ParquetSink.write(facts, cfg.factPath)
      val byGrain = facts.groupBy(col("grain")).count().collect()
        .map(r => s"${r.getString(0)}=${r.getLong(1)}").sorted.mkString(", ")
      log.info(s"bi: wrote ${facts.count()} fact rows [$byGrain] to ${cfg.factPath}")
    } finally facts.unpersist()
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
    readTable(spark, cfg, cfg.orderStatusTable)

  private def readCallStatus(spark: SparkSession, cfg: BiConfig): DataFrame =
    readTable(spark, cfg, cfg.callStatusTable)

  private def readTable(spark: SparkSession, cfg: BiConfig, table: String): DataFrame =
    spark.read
      .format("jdbc")
      .option("url", cfg.sourceJdbcUrl)
      .option("dbtable", table)
      .option("user", cfg.jdbcUser)
      .option("password", cfg.jdbcPassword)
      .option("driver", "org.postgresql.Driver")
      .load()

  private def readCalls(spark: SparkSession, cfg: BiConfig): DataFrame =
    spark.read
      .format("kafka")
      .option("kafka.bootstrap.servers", cfg.kafkaBootstrap)
      .option("subscribe", cfg.callTopic)
      .option("startingOffsets", "earliest")
      .option("endingOffsets", "latest")
      .load()
      .select(
        org.apache.spark.sql.functions
          .from_json(col("value").cast("string"), CallSchema.schema)
          .as("c"))
      .select(col("c.id").as("call_id"), col("c.passengerId").as("passenger_id"))
      .where(col("call_id").isNotNull)

  private def sleepInterruptibly(seconds: Int): Unit = {
    var left = seconds
    while (running && left > 0) {
      Thread.sleep(1000L)
      left -= 1
    }
  }
}
