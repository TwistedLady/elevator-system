package pl.feelcodes.elevator.app.outbound

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import pl.feelcodes.elevator.common.dto.DoorStateDto
import pl.feelcodes.elevator.common.serializable.Json

/** Publishes door open/close changes to Kafka, keyed by elevator name. */
final class DoorStatePublisher(producer: KafkaProducer[String, String], topic: String):
  def publish(dto: DoorStateDto): Unit =
    producer.send(new ProducerRecord[String, String](topic, dto.elevatorName, Json.encode(dto)))
