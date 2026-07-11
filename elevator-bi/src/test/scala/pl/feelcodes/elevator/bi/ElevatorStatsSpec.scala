package pl.feelcodes.elevator.bi

import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.bi.sink.ParquetSink

import java.nio.file.Files

/** Mileage transform + generic ParquetSink round-trip against a local Spark. */
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

  test("batch mileage folds each elevator's events in offset order, independent of input order") {
    val ds = events(
      StateEvent("e1", 1, offset = 2),
      StateEvent("e1", 3, offset = 1),
      StateEvent("e1", 0, offset = 0),
      StateEvent("e2", 5, offset = 0))
    val result = ElevatorStats.mileage(ds, spark).collect().map(r => r.elevatorName -> r.floorsTravelled).toMap
    assert(result === Map("e1" -> 5L, "e2" -> 0L))
  }

  test("ParquetSink writes to a dir and each write atomically replaces the last snapshot") {
    val s = spark
    import s.implicits._
    val dir  = Files.createTempDirectory("fact-parquet")
    val path = dir.resolve("elevator-facts.parquet").toUri.toString

    def readBack(): Map[String, Long] =
      spark.read.parquet(path).collect()
        .map(r => r.getAs[String]("elevator_name") -> r.getAs[Long]("floors_travelled")).toMap

    ParquetSink.write(Seq(("e1", 10L), ("e2", 4L)).toDF("elevator_name", "floors_travelled"), path)
    assert(readBack() === Map("e1" -> 10L, "e2" -> 4L))

    ParquetSink.write(Seq(("e1", 12L)).toDF("elevator_name", "floors_travelled"), path)
    assert(readBack() === Map("e1" -> 12L))
  }
}
