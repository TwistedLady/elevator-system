package pl.feelcodes.elevator.bi

import java.sql.Timestamp
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

class PassengerConflictsSpec extends AnyFunSuite with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit =
    spark = SparkSession.builder()
      .appName("passenger-conflicts-test")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    if (spark != null) spark.stop()

  private def ts(s: String): Timestamp = Timestamp.valueOf(s)

  private def calls(rows: (String, String)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("call_id", "passenger_id")
  }
  private def callStatus(rows: (String, String)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("call_id", "order_id")
  }
  private def orderStatus(rows: (String, String, Timestamp, Timestamp)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("order_id", "elevator_name", "created_at", "done_at")
  }

  private def run(c: DataFrame, cs: DataFrame, os: DataFrame): Seq[(String, String, String)] =
    PassengerConflicts.detect(PassengerConflicts.windows(c, cs, os)).collect()
      .map(r => (r.getAs[String]("passenger_id"), r.getAs[String]("elevator_a"), r.getAs[String]("elevator_b")))
      .toSeq

  test("flags a passenger whose two lifts' served windows overlap") {
    val conflicts = run(
      calls("c1" -> "alice", "c2" -> "alice"),
      callStatus("c1" -> "oa", "c2" -> "ob"),
      orderStatus(
        ("oa", "e1", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:30")),
        ("ob", "e2", ts("2026-07-10 10:00:10"), ts("2026-07-10 10:00:40"))))
    assert(conflicts === Seq(("alice", "e1", "e2")))
  }

  test("sequential windows on two lifts (no time overlap) are clean") {
    val conflicts = run(
      calls("c1" -> "alice", "c2" -> "alice"),
      callStatus("c1" -> "oa", "c2" -> "ob"),
      orderStatus(
        ("oa", "e1", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:30")),
        ("ob", "e2", ts("2026-07-10 10:00:31"), ts("2026-07-10 10:01:00"))))
    assert(conflicts.isEmpty)
  }

  test("a frozen call (no order assigned) is not a served window, so no conflict") {
    // alice rides e1 [10:00,10:30]; her e2 call was frozen the whole time (order_id null).
    val conflicts = run(
      calls("c1" -> "alice", "c2" -> "alice"),
      callStatus("c1" -> "oa", ("c2", null.asInstanceOf[String])),
      orderStatus(("oa", "e1", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:30"))))
    assert(conflicts.isEmpty)
  }

  test("overlapping windows on the same lift are not a two-lift conflict") {
    val conflicts = run(
      calls("c1" -> "alice", "c2" -> "alice"),
      callStatus("c1" -> "oa", "c2" -> "ob"),
      orderStatus(
        ("oa", "e1", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:30")),
        ("ob", "e1", ts("2026-07-10 10:00:10"), ts("2026-07-10 10:00:40"))))
    assert(conflicts.isEmpty)
  }

  test("anonymous calls (no passenger) are ignored") {
    val conflicts = run(
      calls(("c1", null.asInstanceOf[String]), ("c2", null.asInstanceOf[String])),
      callStatus("c1" -> "oa", "c2" -> "ob"),
      orderStatus(
        ("oa", "e1", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:30")),
        ("ob", "e2", ts("2026-07-10 10:00:10"), ts("2026-07-10 10:00:40"))))
    assert(conflicts.isEmpty)
  }

  test("an open window (done_at null) overlaps a concurrent window on another lift") {
    val conflicts = run(
      calls("c1" -> "alice", "c2" -> "alice"),
      callStatus("c1" -> "oa", "c2" -> "ob"),
      orderStatus(
        ("oa", "e1", ts("2026-07-10 10:00:00"), null.asInstanceOf[Timestamp]),
        ("ob", "e2", ts("2026-07-10 10:00:10"), ts("2026-07-10 10:00:40"))))
    assert(conflicts === Seq(("alice", "e1", "e2")))
  }
}
