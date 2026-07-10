package pl.feelcodes.elevator.bi

/** Audit stat for the one-lift-per-passenger invariant: did any passenger's served
  * windows overlap in time across two different lifts?
  *
  * A passenger is "served by lift E" for the life of an order they are on:
  * [order.created_at, order.done_at). passengerId lives only on the elevator-calls
  * topic, so we rebuild the link call -> order -> lift:
  *   calls(id, passengerId)  ->  call_status(call_id, order_id)  ->  order_status(order_id, elevator, created_at, done_at)
  * A frozen call has no order_id yet, so it is (correctly) not a served window and
  * never counts as a conflict — that is exactly what the PassengerManager gate holds back.
  */

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{coalesce, col, greatest, least, lit, to_timestamp}

object PassengerConflicts {

  private val FarFuture = to_timestamp(lit("9999-12-31 00:00:00"))

  /** One (passenger, order) served window on a lift, from the three joined sources. */
  def windows(calls: DataFrame, callStatus: DataFrame, orderStatus: DataFrame): DataFrame =
    calls
      .filter(col("passenger_id").isNotNull)
      .select("call_id", "passenger_id")
      .distinct()
      .join(callStatus.select("call_id", "order_id"), Seq("call_id"))
      .filter(col("order_id").isNotNull)
      .join(
        orderStatus.select("order_id", "elevator_name", "created_at", "done_at"),
        Seq("order_id"))
      .select("passenger_id", "order_id", "elevator_name", "created_at", "done_at")
      .distinct()

  /** Pairs of served windows for the same passenger, on different lifts, overlapping in
    * time. Each unordered pair appears once (order_a < order_b). An open window (done_at
    * null) is treated as still running. */
  def detect(windows: DataFrame): DataFrame = {
    val a = windows.alias("a")
    val b = windows.alias("b")
    a.join(
      b,
      col("a.passenger_id") === col("b.passenger_id")
        && col("a.order_id") < col("b.order_id")
        && col("a.elevator_name") =!= col("b.elevator_name")
        && (col("a.created_at") < coalesce(col("b.done_at"), FarFuture))
        && (col("b.created_at") < coalesce(col("a.done_at"), FarFuture)))
      .select(
        col("a.passenger_id").as("passenger_id"),
        col("a.elevator_name").as("elevator_a"),
        col("a.order_id").as("order_a"),
        col("b.elevator_name").as("elevator_b"),
        col("b.order_id").as("order_b"),
        greatest(col("a.created_at"), col("b.created_at")).as("overlap_start"),
        least(coalesce(col("a.done_at"), FarFuture), coalesce(col("b.done_at"), FarFuture)).as("overlap_end"))
  }
}
