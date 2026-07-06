package pl.feelcodes.elevator.bi

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, count, lit}

/** Pure Spark transform for the "orders served" BI: how many times each elevator reached an ordered
  * floor.
  *
  * When a car reaches an order's floor the app marks that order DONE (order_status.status = 'DONE').
  * So "times reached an ordered floor" = count of DONE rows per elevator. (The elevator-state Kafka
  * topic can't answer this — its published tag is always empty — so we read the order_status
  * read-model instead.)
  */
object OrdersServed {

  /** order_status rows -> (elevator_name, orders_served) counting only DONE rows. */
  def tally(orderStatus: DataFrame): DataFrame =
    orderStatus
      .filter(col("status") === "DONE")
      .groupBy(col("elevator_name"))
      .agg(count(lit(1)).as("orders_served"))
}
