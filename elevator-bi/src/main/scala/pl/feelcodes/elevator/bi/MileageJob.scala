package pl.feelcodes.elevator.bi

import org.apache.spark.sql.functions.col
import org.apache.spark.sql.streaming.{GroupState, GroupStateTimeout, OutputMode, Trigger}
import org.apache.spark.sql.{Dataset, SparkSession}
import pl.feelcodes.elevator.bi.config.BiConfig
import pl.feelcodes.elevator.bi.kafka.ElevatorStateSchema
import pl.feelcodes.elevator.bi.sink.PostgresMileageSink

/** One `elevator-state` event, projected to the two fields mileage needs plus the Kafka offset
  * (used to order events within a micro-batch, since a batch's per-key iterator is unordered). */
final case class StateEvent(elevatorName: String, floor: Int, offset: Long)

/** A row of computed running mileage, sunk to Postgres. */
final case class MileageRow(elevatorName: String, floorsTravelled: Long)

/** Spark Structured Streaming BI job: reads `elevator-state`, keeps per-elevator running mileage
  * with arbitrary stateful processing, and upserts totals into Postgres each micro-batch.
  */
object MileageJob {

  def main(args: Array[String]): Unit = {
    val cfg = BiConfig.fromEnv()
    val spark = SparkSession.builder().appName("elevator-mileage").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    try run(spark, cfg)
    finally spark.stop()
  }

  def run(spark: SparkSession, cfg: BiConfig): Unit = {
    import spark.implicits._

    val events: Dataset[StateEvent] = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", cfg.kafkaBootstrap)
      .option("subscribe", cfg.stateTopic)
      .option("startingOffsets", cfg.startingOffsets)
      .load()
      .select(
        org.apache.spark.sql.functions
          .from_json(col("value").cast("string"), ElevatorStateSchema.schema)
          .as("s"),
        col("offset").as("offset"))
      .select(col("s.elevatorName").as("elevatorName"), col("s.floor").as("floor"), col("offset"))
      .where(col("elevatorName").isNotNull.and(col("floor").isNotNull))
      .as[StateEvent]

    val mileage: Dataset[MileageRow] = events
      .groupByKey(_.elevatorName)
      .flatMapGroupsWithState[MileageState, MileageRow](
        OutputMode.Update(),
        GroupStateTimeout.NoTimeout()) { (elevatorName, rows, state) =>
        val orderedFloors = rows.toSeq.sortBy(_.offset).map(_.floor)
        Mileage.update(state.getOption, orderedFloors) match {
          case Some(updated) =>
            state.update(updated)
            Iterator(MileageRow(elevatorName, updated.floorsTravelled))
          case None =>
            Iterator.empty
        }
      }

    val query = mileage.writeStream
      .outputMode(OutputMode.Update())
      .option("checkpointLocation", cfg.checkpointLocation)
      .trigger(Trigger.ProcessingTime(cfg.triggerInterval))
      .foreachBatch { (batch: Dataset[MileageRow], _: Long) =>
        PostgresMileageSink.upsert(batch, cfg)
      }
      .start()

    query.awaitTermination()
  }
}
