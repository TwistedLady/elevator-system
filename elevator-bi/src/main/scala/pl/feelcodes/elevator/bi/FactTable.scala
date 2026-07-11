package pl.feelcodes.elevator.bi

/** The single BI fact table — one Parquet file for the whole module, detailed enough
  * that every stat is a downstream view. Grain is tagged by the `grain` column:
  *
  *   - ELEVATOR: one row per lift, carrying floors_travelled (mileage). Folded here
  *     because its source is the elevator-state topic, which the api's DuckDB layer
  *     cannot read — everything else the api derives itself.
  *   - ORDER:    one row per order (a lift's leg of service) with its lifecycle timing
  *     and service duration.
  *   - CALL:     one row per passenger call, with its own received->done timing AND the
  *     served window (served_from/served_to) of the order it was assigned to, so
  *     per-passenger conflict detection needs only CALL rows.
  *
  * Columns are a nullable superset across grains (activity/OBT style); the api's views
  * filter by `grain` and compute served counts, call latency and passenger conflicts.
  */

import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.{Column, DataFrame, Dataset, SparkSession}

object FactTable {

  private def secondsBetween(from: Column, to: Column): Column =
    to.cast("double") - from.cast("double")

  def build(mileage: Dataset[MileageRow], orderStatus: DataFrame, callStatus: DataFrame,
            calls: DataFrame, spark: SparkSession): DataFrame = {

    val elevatorRows = mileage.toDF("elevator_name", "floors_travelled").select(
      lit("ELEVATOR").as("grain"),
      col("elevator_name"),
      lit(null).cast("int").as("floor"),
      col("floors_travelled").cast("long"),
      lit(null).cast("string").as("order_id"),
      lit(null).cast("string").as("call_id"),
      lit(null).cast("string").as("passenger_id"),
      lit(null).cast("string").as("status"),
      lit(null).cast("boolean").as("is_done"),
      lit(null).cast("timestamp").as("created_at"),
      lit(null).cast("timestamp").as("done_at"),
      lit(null).cast("double").as("processing_seconds"),
      lit(null).cast("timestamp").as("served_from"),
      lit(null).cast("timestamp").as("served_to"))

    val orderRows = orderStatus.select(
      lit("ORDER").as("grain"),
      col("elevator_name"),
      col("floor").cast("int"),
      lit(null).cast("long").as("floors_travelled"),
      col("order_id"),
      lit(null).cast("string").as("call_id"),
      lit(null).cast("string").as("passenger_id"),
      col("status"),
      (col("status") === "DONE").as("is_done"),
      col("created_at").cast("timestamp"),
      col("done_at").cast("timestamp"),
      secondsBetween(col("created_at"), col("done_at")).as("processing_seconds"),
      col("created_at").cast("timestamp").as("served_from"),
      col("done_at").cast("timestamp").as("served_to"))

    // The served window of each order, to hang onto its calls for conflict detection.
    val orderWindow = orderStatus.select(
      col("order_id").as("ow_order_id"),
      col("created_at").cast("timestamp").as("ow_from"),
      col("done_at").cast("timestamp").as("ow_to"))

    val callRows = callStatus
      .join(calls.select(col("call_id"), col("passenger_id")), Seq("call_id"), "left")
      .join(orderWindow, callStatus("order_id") === col("ow_order_id"), "left")
      .select(
        lit("CALL").as("grain"),
        callStatus("elevator_name"),
        callStatus("floor").cast("int"),
        lit(null).cast("long").as("floors_travelled"),
        callStatus("order_id"),
        callStatus("call_id"),
        col("passenger_id"),
        callStatus("status"),
        (callStatus("status") === "DONE").as("is_done"),
        callStatus("created_at").cast("timestamp"),
        callStatus("done_at").cast("timestamp"),
        secondsBetween(callStatus("created_at"), callStatus("done_at")).as("processing_seconds"),
        col("ow_from").as("served_from"),
        col("ow_to").as("served_to"))

    elevatorRows.unionByName(orderRows).unionByName(callRows)
  }
}
