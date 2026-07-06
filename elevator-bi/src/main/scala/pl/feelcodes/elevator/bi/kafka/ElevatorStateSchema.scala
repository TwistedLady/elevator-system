package pl.feelcodes.elevator.bi.kafka

import org.apache.spark.sql.types.{DataTypes, StructType}

/** Wire schema of the `elevator-state` Kafka value (JSON produced by elevator-app).
  *
  * Mirrors `ElevatorStateDto{tag, elevatorName, direction, motion, floor}`. We parse by contract
  * rather than depend on the Scala 3 `elevator-common-dto` — only the fields mileage needs are typed
  * strictly; the rest are declared for completeness / future BI jobs.
  */
object ElevatorStateSchema {
  val schema: StructType = new StructType()
    .add("tag", DataTypes.StringType)
    .add("elevatorName", DataTypes.StringType)
    .add("direction", DataTypes.StringType)
    .add("motion", DataTypes.StringType)
    .add("floor", DataTypes.IntegerType)
}
