package pl.feelcodes.elevator.bi

import java.sql.Timestamp
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

class CallLatencySpec extends AnyFunSuite with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit =
    spark = SparkSession.builder()
      .appName("call-latency-test")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    if (spark != null) spark.stop()

  private def ts(s: String): Timestamp = Timestamp.valueOf(s)

  // call_id, elevator_name, floor, order_id, created_at, done_at
  private def callStatus(rows: (String, String, Int, String, Timestamp, Timestamp)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("call_id", "elevator_name", "floor", "order_id", "created_at", "done_at")
  }

  private def near(a: Double, b: Double, eps: Double = 1e-6): Boolean = math.abs(a - b) <= eps

  private def perCallSeconds(cs: DataFrame): Map[String, Double] =
    CallLatency.perCall(cs).collect()
      .map(r => r.getAs[String]("call_id") -> r.getAs[Double]("processing_seconds"))
      .toMap

  private def summary(cs: DataFrame): Map[String, org.apache.spark.sql.Row] =
    CallLatency.summary(CallLatency.perCall(cs)).collect()
      .map(r => r.getAs[String]("elevator_name") -> r)
      .toMap

  test("processing time is done_at - created_at in seconds, sub-second precision") {
    val secs = perCallSeconds(callStatus(
      ("c1", "e1", 3, "o1", ts("2026-07-10 10:00:00.0"), ts("2026-07-10 10:00:02.5")),
      ("c2", "e1", 7, "o2", ts("2026-07-10 10:00:00.0"), ts("2026-07-10 10:00:00.1"))))
    assert(near(secs("c1"), 2.5))
    assert(near(secs("c2"), 0.1))
  }

  test("pending calls (no done_at) are excluded") {
    val secs = perCallSeconds(callStatus(
      ("c1", "e1", 3, "o1", ts("2026-07-10 10:00:00.0"), ts("2026-07-10 10:00:01.0")),
      ("c2", "e1", 4, null.asInstanceOf[String], ts("2026-07-10 10:00:00.0"), null.asInstanceOf[Timestamp])))
    assert(secs.keySet === Set("c1"))
  }

  test("summary reports count/avg/min/max per elevator plus a fleet-wide ALL row") {
    val byLift = summary(callStatus(
      ("c1", "e1", 1, "o1", ts("2026-07-10 10:00:00.0"), ts("2026-07-10 10:00:02.0")),
      ("c2", "e1", 2, "o2", ts("2026-07-10 10:00:00.0"), ts("2026-07-10 10:00:04.0")),
      ("c3", "e2", 3, "o3", ts("2026-07-10 10:00:00.0"), ts("2026-07-10 10:00:10.0"))))

    val e1 = byLift("e1")
    assert(e1.getAs[Long]("calls") === 2L)
    assert(near(e1.getAs[Double]("avg_seconds"), 3.0))
    assert(near(e1.getAs[Double]("min_seconds"), 2.0))
    assert(near(e1.getAs[Double]("max_seconds"), 4.0))

    val all = byLift("ALL")
    assert(all.getAs[Long]("calls") === 3L)
    assert(near(all.getAs[Double]("min_seconds"), 2.0))
    assert(near(all.getAs[Double]("max_seconds"), 10.0))
    assert(near(all.getAs[Double]("avg_seconds"), (2.0 + 4.0 + 10.0) / 3))
  }

  test("percentiles land on the distribution (p50 median, p95 near the top)") {
    val rows = (1 to 100).map { i =>
      ("c" + i, "e1", i % 10, "o" + i, ts("2026-07-10 10:00:00.0"),
        ts(f"2026-07-10 10:00:${i}%02d.0"))
    }
    val all = summary(callStatus(rows: _*))("ALL")
    assert(near(all.getAs[Double]("p50_seconds"), 50.0, 1.0))
    assert(near(all.getAs[Double]("p95_seconds"), 95.0, 1.0))
  }
}
