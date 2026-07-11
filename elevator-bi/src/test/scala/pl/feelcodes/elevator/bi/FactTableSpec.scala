package pl.feelcodes.elevator.bi

import java.sql.Timestamp
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

/** The single fact table: correct grain tagging, derived durations, and the served
  * window hung onto CALL rows so the api can detect conflicts from them alone. */
class FactTableSpec extends AnyFunSuite with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit =
    spark = SparkSession.builder()
      .appName("fact-table-test")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    if (spark != null) spark.stop()

  private def ts(s: String): Timestamp = Timestamp.valueOf(s)

  private def mileage(rows: (String, Long)*) = {
    val s = spark; import s.implicits._
    rows.map { case (e, m) => MileageRow(e, m) }.toDS()
  }
  // order_id, elevator_name, floor, status, created_at, done_at
  private def orderStatus(rows: (String, String, Int, String, Timestamp, Timestamp)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("order_id", "elevator_name", "floor", "status", "created_at", "done_at")
  }
  // call_id, elevator_name, floor, order_id, status, created_at, done_at
  private def callStatus(rows: (String, String, Int, String, String, Timestamp, Timestamp)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("call_id", "elevator_name", "floor", "order_id", "status", "created_at", "done_at")
  }
  private def calls(rows: (String, String)*): DataFrame = {
    val s = spark; import s.implicits._
    rows.toSeq.toDF("call_id", "passenger_id")
  }

  private def build(m: org.apache.spark.sql.Dataset[MileageRow], os: DataFrame, cs: DataFrame, c: DataFrame): Seq[Row] =
    FactTable.build(m, os, cs, c, spark).collect().toSeq

  private def secs(r: Row): Double = r.getAs[Double]("processing_seconds")

  test("tags one row per grain with the right measures") {
    val rows = build(
      mileage("e1" -> 12L),
      orderStatus(("o1", "e1", 3, "DONE", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:04"))),
      callStatus(("c1", "e1", 3, "o1", "DONE", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:02.5"))),
      calls("c1" -> "alice"))

    val byGrain = rows.groupBy(_.getAs[String]("grain")).map { case (g, rs) => g -> rs.size }
    assert(byGrain === Map("ELEVATOR" -> 1, "ORDER" -> 1, "CALL" -> 1))

    val elevator = rows.find(_.getAs[String]("grain") == "ELEVATOR").get
    assert(elevator.getAs[Long]("floors_travelled") === 12L)

    val order = rows.find(_.getAs[String]("grain") == "ORDER").get
    assert(order.getAs[Boolean]("is_done"))
    assert(secs(order) === 4.0)

    val call = rows.find(_.getAs[String]("grain") == "CALL").get
    assert(call.getAs[String]("passenger_id") === "alice")
    assert(secs(call) === 2.5) // call latency: received -> done
  }

  test("CALL rows carry the served window of their assigned order (for conflict detection)") {
    val rows = build(
      mileage("e1" -> 0L),
      orderStatus(("o1", "e1", 5, "DONE", ts("2026-07-10 10:00:00"), ts("2026-07-10 10:00:09"))),
      callStatus(("c1", "e1", 5, "o1", "DONE", ts("2026-07-10 10:00:01"), ts("2026-07-10 10:00:09"))),
      calls("c1" -> "bob"))

    val call = rows.find(_.getAs[String]("grain") == "CALL").get
    assert(call.getAs[Timestamp]("served_from") === ts("2026-07-10 10:00:00")) // order created
    assert(call.getAs[Timestamp]("served_to") === ts("2026-07-10 10:00:09"))   // order done
  }

  test("a frozen call (no order) and an anonymous call still produce CALL rows with nulls") {
    val rows = build(
      mileage("e1" -> 0L),
      orderStatus(),
      callStatus(
        ("c1", "e1", 2, null.asInstanceOf[String], "PROGRESS", ts("2026-07-10 10:00:00"), null.asInstanceOf[Timestamp]),
        ("c2", "e1", 4, null.asInstanceOf[String], "PROGRESS", ts("2026-07-10 10:00:00"), null.asInstanceOf[Timestamp])),
      calls("c1" -> "carol")) // c2 anonymous (absent from calls topic)

    val callRows = rows.filter(_.getAs[String]("grain") == "CALL")
    assert(callRows.size === 2)
    val c1 = callRows.find(_.getAs[String]("call_id") == "c1").get
    assert(c1.getAs[String]("passenger_id") === "carol")
    assert(c1.isNullAt(c1.fieldIndex("served_from")))       // no order -> no served window
    assert(c1.isNullAt(c1.fieldIndex("processing_seconds"))) // not done -> no duration
    val c2 = callRows.find(_.getAs[String]("call_id") == "c2").get
    assert(c2.isNullAt(c2.fieldIndex("passenger_id")))       // anonymous
  }
}
