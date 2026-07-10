package pl.feelcodes.elevator.bi.kafka

import org.apache.spark.sql.types.{DataTypes, StructType}

/** Wire schema of the elevator-calls Kafka JSON value (mirrors CallDto). Only id +
  * passengerId matter here — they tie a call to a person; parsed by contract to avoid
  * depending on the Scala 3 elevator-common-dto.
  */
object CallSchema {
  val schema: StructType = new StructType()
    .add("id", DataTypes.StringType)
    .add("elevatorName", DataTypes.StringType)
    .add("floor", DataTypes.IntegerType)
    .add("passengerId", DataTypes.StringType)
}
