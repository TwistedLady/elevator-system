package pl.feelcodes.elevator.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer, StringSerializer}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Flow, Keep}
import org.apache.pekko.util.Timeout
import pl.feelcodes.elevator.app.actors.Coordinator
import pl.feelcodes.elevator.common.dto.{ElevatorStateDto, ElevatorOrderDto}

import java.util.Properties

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

object Json {
  private val mapper = ObjectMapper().registerModule(DefaultScalaModule)

  def encode[T](obj: T): String = mapper.writeValueAsString(obj)

  def decode[T](bytes: Array[Byte], clazz: Class[T]): T = mapper.readValue(bytes, clazz)
}

object Kafka {
  private final case class KafkaConf(bootstrapServers: String,
                                     groupId: String,
                                     commandTopic: String)

  def runKafkaToCoordinator(system: ActorSystem[?],
                            coordinatorProvider: String => EntityRef[Coordinator.Command]): Unit = {
    given ActorSystem[?] = system
    given ExecutionContext = system.executionContext
    given Timeout = 3.seconds

    val cfg = readKafkaConf(system.settings.config)

    val consumerSettings =
      ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
        .withBootstrapServers(cfg.bootstrapServers)
        .withGroupId(cfg.groupId)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    val committerSettings = CommitterSettings(system)

    val toCoordinator: Flow[CommittableMessage[String, Array[Byte]], CommittableOffset, ?] =
      Flow[CommittableMessage[String, Array[Byte]]]
        .mapAsync(1) { msg =>
          val dto = Json.decode(msg.record.value(), classOf[ElevatorOrderDto])
          val coordinator = coordinatorProvider(dto.elevatorName)

          coordinator
            .ask[Coordinator.Ack](replyTo => Coordinator.Process(dto, replyTo))
            .map(_ => msg.committableOffset)
        }

    val (killSwitch, done) =
      Consumer
        .committableSource(consumerSettings, Subscriptions.topics(cfg.commandTopic))
        .viaMat(KillSwitches.single)(Keep.right)
        .via(toCoordinator)
        .toMat(Committer.sink(committerSettings))(Keep.both)
        .run()

    done.failed.foreach(ex => system.log.error("Kafka stream failed", ex))

    system
      .classicSystem
      .registerOnTermination(() => killSwitch.shutdown())
  }

  private def readKafkaConf(root: Config): KafkaConf = {
    val cfg = root.getConfig("elevator.kafka")

    KafkaConf(
      bootstrapServers = cfg.getString("bootstrap-servers"),
      groupId = cfg.getString("group-id"),
      commandTopic = cfg.getString("command-topic")
    )
  }
}

/** Publishes confirmed elevator state to the `elevator-state` topic so the Spring API
  * (and anything else) can monitor movement. Keyed by elevator name. */
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