package pl.feelcodes.elevator.bi

import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

/** One `elevator-state` event, projected to the two fields mileage needs plus the Kafka offset
  * (used to order events per elevator, since Kafka only guarantees order within a partition). */
final case class StateEvent(elevatorName: String, floor: Int, offset: Long)

/** Per-elevator running mileage = total floors travelled. */
final case class MileageRow(elevatorName: String, floorsTravelled: Long)

/** One row of the unified BI read-model: everything we know about a single elevator. */
final case class StatsRow(elevatorName: String, floorsTravelled: Long, ordersServed: Long)

/** Spark transforms that turn raw events + order counts into the one-row-per-elevator model.
  *
  * Kept as small, individually testable steps (see `ElevatorStatsSpec`, run against a local Spark).
  * The mileage arithmetic itself stays in the pure, Spark-free [[Mileage]].
  */
object ElevatorStats {

  /** Batch mileage: fold every event of an elevator (ordered by offset) through [[Mileage.update]].
    * Replaces the old streaming/stateful job — the fleet is tiny, so a full re-scan each cycle is
    * cheap and needs no checkpoint. */
  def mileage(events: Dataset[StateEvent], spark: SparkSession): Dataset[MileageRow] = {
    import spark.implicits._
    events
      .groupByKey(_.elevatorName)
      .mapGroups { (elevatorName, rows) =>
        val floors = rows.toSeq.sortBy(_.offset).map(_.floor)
        val travelled = Mileage.update(None, floors).map(_.floorsTravelled).getOrElse(0L)
        MileageRow(elevatorName, travelled)
      }
  }

  /** Full-outer join mileage with the "orders served" tally into one row per elevator. An elevator
    * that only appears on one side gets 0 for the missing metric. */
  def join(mileage: Dataset[MileageRow], served: DataFrame, spark: SparkSession): Dataset[StatsRow] = {
    import spark.implicits._
    mileage.toDF("elevator_name", "floors_travelled")
      .join(served, Seq("elevator_name"), "full_outer")
      .na.fill(0L, Seq("floors_travelled", "orders_served"))
      .select(
        col("elevator_name").as("elevatorName"),
        col("floors_travelled").as("floorsTravelled"),
        col("orders_served").as("ordersServed"))
      .as[StatsRow]
  }
}
