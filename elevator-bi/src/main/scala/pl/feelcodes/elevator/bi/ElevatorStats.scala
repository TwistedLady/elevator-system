package pl.feelcodes.elevator.bi

/** Spark transforms: raw state events + order counts -> one row per elevator.
  * Events are ordered by Kafka offset (Kafka only orders within a partition);
  * the fleet is tiny so a full re-scan each cycle is cheap, no checkpoint. Pure
  * mileage math lives in [[Mileage]].
  */

import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}

final case class StateEvent(elevatorName: String, floor: Int, offset: Long)

final case class MileageRow(elevatorName: String, floorsTravelled: Long)

final case class StatsRow(elevatorName: String, floorsTravelled: Long, ordersServed: Long)

object ElevatorStats {

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
