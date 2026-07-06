package pl.feelcodes.elevator.bi

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.LoggerFactory
import pl.feelcodes.elevator.bi.config.BiConfig
import pl.feelcodes.elevator.bi.sink.PostgresOrdersServedSink

/** Spark BATCH BI job: counts how many times each elevator reached an ordered floor (= completed
  * orders) by reading the `order_status` read-model over JDBC, and upserts the counts into a
  * `elevator_orders_served` table on a fixed interval.
  *
  * Batch (not streaming) because the signal lives in a Postgres table, not a Kafka stream — the
  * `elevator-state` topic carries no order tag. The job loops so the counts stay fresh.
  */
object OrdersServedJob {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val cfg = BiConfig.fromEnv()
    val spark = SparkSession.builder().appName("elevator-orders-served").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    try run(spark, cfg)
    finally spark.stop()
  }

  @volatile private var running = true

  def run(spark: SparkSession, cfg: BiConfig): Unit = {
    sys.addShutdownHook { running = false }
    log.info(s"orders-served: refreshing ${cfg.servedTable} every ${cfg.servedIntervalSeconds}s")

    while (running) {
      refreshOnce(spark, cfg)
      sleepInterruptibly(cfg.servedIntervalSeconds)
    }
  }

  /** One pass: read order_status, tally DONE per elevator, upsert. */
  def refreshOnce(spark: SparkSession, cfg: BiConfig): Unit = {
    val orderStatus = readOrderStatus(spark, cfg)
    val served = OrdersServed.tally(orderStatus)
    PostgresOrdersServedSink.upsert(served, cfg)
    log.info(s"orders-served: upserted ${served.count()} elevator rows into ${cfg.servedTable}")
  }

  private def readOrderStatus(spark: SparkSession, cfg: BiConfig): DataFrame =
    spark.read
      .format("jdbc")
      .option("url", cfg.jdbcUrl)
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
