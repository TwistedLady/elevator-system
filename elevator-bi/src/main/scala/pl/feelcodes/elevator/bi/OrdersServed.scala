package pl.feelcodes.elevator.bi

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, count, lit}

/** Orders served = count of DONE order_status rows per elevator. Read from the
  * order_status read-model, not the elevator-state topic (its tag is always empty).
  */
object OrdersServed {

  def tally(orderStatus: DataFrame): DataFrame =
    orderStatus
      .filter(col("status") === "DONE")
      .groupBy(col("elevator_name"))
      .agg(count(lit(1)).as("orders_served"))
}
