package pl.feelcodes.elevator.app.outbound

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.pekko.actor.typed.ActorSystem

import java.util.Properties

/** The three Kafka state publishers, sharing one producer built from `elevator.kafka` config. */
final case class Publishers(elevator: ElevatorStatePublisher,
                            order: OrderStatePublisher,
                            call: CallStatePublisher)

object Publishers:
  def apply(system: ActorSystem[?]): Publishers =
    val cfg = system.settings.config.getConfig("elevator.kafka")

    val props = new Properties()
    props.put("bootstrap.servers", cfg.getString("bootstrap-servers"))
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)

    val producer = new KafkaProducer[String, String](props)
    system.classicSystem.registerOnTermination(() => producer.close())

    Publishers(
      new ElevatorStatePublisher(producer, cfg.getString("state-topic")),
      new OrderStatePublisher(producer, cfg.getString("order-state-topic")),
      new CallStatePublisher(producer, cfg.getString("call-state-topic")))
