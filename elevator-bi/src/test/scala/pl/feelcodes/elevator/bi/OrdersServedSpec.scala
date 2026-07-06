package pl.feelcodes.elevator.bi

import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

class OrdersServedSpec extends AnyFunSuite with BeforeAndAfterAll {

  private var spark: SparkSession = _

  override def beforeAll(): Unit =
    spark = SparkSession.builder()
      .appName("orders-served-test")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    if (spark != null) spark.stop()

  private def orderStatus(rows: (String, String, Int, String)*) = {
    val s = spark
    import s.implicits._
    rows.toSeq.toDF("tag", "elevator_name", "floor", "status")
  }

  private def tallyMap(df: org.apache.spark.sql.DataFrame): Map[String, Long] =
    OrdersServed.tally(df).collect()
      .map(r => r.getAs[String]("elevator_name") -> r.getAs[Long]("orders_served"))
      .toMap

  test("counts DONE rows per elevator and ignores PROGRESS") {
    val df = orderStatus(
      ("t1", "e1", 5, "DONE"),
      ("t2", "e1", 3, "DONE"),
      ("t3", "e1", 7, "PROGRESS"),
      ("t4", "e2", 1, "DONE"))
    assert(tallyMap(df) === Map("e1" -> 2L, "e2" -> 1L))
  }

  test("no DONE rows yields an empty result") {
    val df = orderStatus(("t1", "e1", 5, "PROGRESS"))
    assert(tallyMap(df).isEmpty)
  }
}
