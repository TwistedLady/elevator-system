package pl.feelcodes.elevator.app.outbound

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import pl.feelcodes.elevator.common.dto.CallStateDto
import pl.feelcodes.elevator.common.serializable.Json

/** Publishes call-state changes to Kafka, keyed by elevator name. */
final class CallStatePublisher(producer: KafkaProducer[String, String], topic: String):
  def publish(dto: CallStateDto): Unit =
    producer.send(new ProducerRecord[String, String](topic, dto.elevatorName, Json.encode(dto)))
