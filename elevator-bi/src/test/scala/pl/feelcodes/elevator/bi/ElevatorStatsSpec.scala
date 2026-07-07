package pl.feelcodes.elevator.bi

import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.bi.config.BiConfig
import pl.feelcodes.elevator.bi.sink.ParquetSink

import java.nio.file.Files

class ElevatorStatsSpec extends AnyFunSuite with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit =
    spark = SparkSession.builder()
      .appName("elevator-stats-test")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    if (spark != null) spark.stop()

  private def events(rows: StateEvent*): org.apache.spark.sql.Dataset[StateEvent] = {
    val s = spark
    import s.implicits._
    rows.toSeq.toDS()
  }

  private def orderStatus(rows: (String, String, Int, String)*) = {
    val s = spark
    import s.implicits._
    rows.toSeq.toDF("tag", "elevator_name", "floor", "status")
  }

  private def statsMap(ds: org.apache.spark.sql.Dataset[StatsRow]): Map[String, (Long, Long)] =
    ds.collect().map(r => r.elevatorName -> (r.floorsTravelled, r.ordersServed)).toMap

  test("batch mileage folds each elevator's events in offset order, independent of input order") {
    // e1: 0 -> 3 (3) -> 1 (2) = 5 ; fed out of order to prove sorting by offset
    val ds = events(
      StateEvent("e1", 1, offset = 2),
      StateEvent("e1", 3, offset = 1),
      StateEvent("e1", 0, offset = 0),
      StateEvent("e2", 5, offset = 0))
    val result = ElevatorStats.mileage(ds, spark).collect().map(r => r.elevatorName -> r.floorsTravelled).toMap
    assert(result === Map("e1" -> 5L, "e2" -> 0L))
  }

  test("join yields one row per elevator, zero-filling a metric missing on either side") {
    // e1 has both; e2 only moved (no DONE orders); e3 only served (no state events)
    val mileage = ElevatorStats.mileage(
      events(StateEvent("e1", 0, 0), StateEvent("e1", 2, 1),
             StateEvent("e2", 4, 0), StateEvent("e2", 6, 1)), spark)
    val served = OrdersServed.tally(orderStatus(
      ("t1", "e1", 2, "DONE"),
      ("t2", "e3", 1, "DONE")))

    assert(statsMap(ElevatorStats.join(mileage, served, spark)) === Map(
      "e1" -> (2L, 1L),
      "e2" -> (2L, 0L),
      "e3" -> (0L, 1L)))
  }

  test("ParquetSink writes one row per elevator and each write replaces the last snapshot") {
    val s = spark
    import s.implicits._
    val dir = Files.createTempDirectory("stats-parquet")
    val cfg = BiConfig.fromEnv().copy(parquetPath = dir.resolve("elevators.parquet").toUri.toString)

    def readBack(): Map[String, (Long, Long)] =
      spark.read.parquet(cfg.parquetPath).as[StatsRow].collect()
        .map(r => r.elevatorName -> (r.floorsTravelled, r.ordersServed)).toMap

    ParquetSink.write(Seq(StatsRow("e1", 10L, 2L), StatsRow("e2", 4L, 0L)).toDS(), cfg)
    assert(readBack() === Map("e1" -> (10L, 2L), "e2" -> (4L, 0L)))

    // second write is a full overwrite, not an append
    ParquetSink.write(Seq(StatsRow("e1", 12L, 3L)).toDS(), cfg)
    assert(readBack() === Map("e1" -> (12L, 3L)))
  }
}
