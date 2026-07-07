package pl.feelcodes.elevator.app.outbound

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.serializable.Json

/** Publishes elevator-state changes to Kafka, keyed by elevator name. */
final class ElevatorStatePublisher(producer: KafkaProducer[String, String], topic: String):
  def publish(dto: ElevatorStateDto): Unit =
    producer.send(new ProducerRecord[String, String](topic, dto.elevatorName, Json.encode(dto)))
