package pl.feelcodes.elevator.app.kafka

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.actor.typed.ActorSystem
import pl.feelcodes.elevator.common.dto.ElevatorStateDto

import java.util.Properties

/** Outbound Kafka boundary: publishes confirmed elevator state to the `elevator-state` topic so
  * the Spring API (and anything else) can monitor movement. Keyed by elevator name. */
final class StatePublisher(producer: KafkaProducer[String, String], topic: String) {
  def publish(dto: ElevatorStateDto): Unit =
    producer.send(new ProducerRecord[String, String](topic, dto.elevatorName, Json.encode(dto)))
}

object StatePublisher {
  def apply(system: ActorSystem[?]): StatePublisher = {
    val cfg = system.settings.config.getConfig("elevator.kafka")

    val props = new Properties()
    props.put("bootstrap.servers", cfg.getString("bootstrap-servers"))
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)

    val producer = new KafkaProducer[String, String](props)
    system.classicSystem.registerOnTermination(() => producer.close())

    new StatePublisher(producer, cfg.getString("state-topic"))
  }
}
