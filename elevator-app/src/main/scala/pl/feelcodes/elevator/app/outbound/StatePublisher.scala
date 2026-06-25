package pl.feelcodes.elevator.app.outbound

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.actor.typed.ActorSystem
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.serializable.Json

import java.util.Properties

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
