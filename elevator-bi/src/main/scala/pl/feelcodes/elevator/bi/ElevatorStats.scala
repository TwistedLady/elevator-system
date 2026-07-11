package pl.feelcodes.elevator.bi

/** Mileage transform: raw state events -> floors travelled per elevator. Events are
  * ordered by Kafka offset (Kafka only orders within a partition); the fleet is tiny so
  * a full re-scan each cycle is cheap, no checkpoint. Pure mileage math lives in
  * [[Mileage]]. Mileage is the one aggregate the bi job pre-folds (its source is the
  * state topic); every other stat is a downstream DuckDB view over [[FactTable]].
  */

import org.apache.spark.sql.{Dataset, SparkSession}

final case class StateEvent(elevatorName: String, floor: Int, offset: Long)

final case class MileageRow(elevatorName: String, floorsTravelled: Long)

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
}
