package pl.feelcodes.elevator.bi.kafka

import org.apache.spark.sql.types.{DataTypes, StructType}

/** Wire schema of the elevator-state Kafka JSON value (mirrors ElevatorStateDto).
  * Parsed by contract to avoid depending on the Scala 3 elevator-common-dto.
  */
object ElevatorStateSchema {
  val schema: StructType = new StructType()
    .add("elevatorName", DataTypes.StringType)
    .add("direction", DataTypes.StringType)
    .add("motion", DataTypes.StringType)
    .add("floor", DataTypes.IntegerType)
}
