package pl.feelcodes.elevator.app.outbound

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import pl.feelcodes.elevator.common.dto.OrderStateDto
import pl.feelcodes.elevator.common.serializable.Json

/** Publishes order-state changes to Kafka, keyed by elevator name. */
final class OrderStatePublisher(producer: KafkaProducer[String, String], topic: String):
  def publish(dto: OrderStateDto): Unit =
    producer.send(new ProducerRecord[String, String](topic, dto.elevatorName, Json.encode(dto)))
