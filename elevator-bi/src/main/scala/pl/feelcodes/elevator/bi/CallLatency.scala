package pl.feelcodes.elevator.bi

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{avg, col, count, lit, max, min, percentile_approx}

/** Call processing time: how long the system took to serve a passenger's call, from
  * CallReceived (call_status.created_at) to CallDone (call_status.done_at). Both stamps
  * are already materialized on the call_status read model, so no new event is needed.
  * Only completed calls (done_at present) have a duration; pending/frozen calls are
  * excluded. Durations are wall-clock at the read-side projection, so they include a
  * little projection lag on top of true end-to-end time.
  */
object CallLatency {

  /** One row per completed call with its processing time in seconds. Kept as a double
    * for sub-second precision — the fast engine moves a floor in 100ms. */
  def perCall(callStatus: DataFrame): DataFrame =
    callStatus
      .filter(col("created_at").isNotNull && col("done_at").isNotNull)
      .select(
        col("call_id"),
        col("elevator_name"),
        col("floor"),
        col("order_id"),
        col("created_at"),
        col("done_at"),
        (col("done_at").cast("double") - col("created_at").cast("double")).as("processing_seconds"))

  /** Processing-time summary per elevator plus a fleet-wide "ALL" row: how many calls,
    * average, fastest, slowest, median (p50) and tail (p95). */
  def summary(perCall: DataFrame): DataFrame = {
    val metrics = Seq(
      count(lit(1)).as("calls"),
      avg(col("processing_seconds")).as("avg_seconds"),
      min(col("processing_seconds")).as("min_seconds"),
      max(col("processing_seconds")).as("max_seconds"),
      percentile_approx(col("processing_seconds"), lit(0.5), lit(10000)).as("p50_seconds"),
      percentile_approx(col("processing_seconds"), lit(0.95), lit(10000)).as("p95_seconds"))

    val perElevator = perCall.groupBy(col("elevator_name")).agg(metrics.head, metrics.tail: _*)
    val fleet       = perCall.groupBy(lit("ALL").as("elevator_name")).agg(metrics.head, metrics.tail: _*)
    perElevator.unionByName(fleet)
  }
}
