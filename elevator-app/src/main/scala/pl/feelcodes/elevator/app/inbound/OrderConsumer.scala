package pl.feelcodes.elevator.app.inbound

import com.typesafe.config.Config
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.kafka.ConsumerMessage.{CommittableMessage, CommittableOffset}
import org.apache.pekko.kafka.scaladsl.{Committer, Consumer}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Flow, Keep}
import pl.feelcodes.elevator.app.actors.Coordinator
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol.AddOriginalStream
import pl.feelcodes.elevator.common.serializable.Json

import scala.concurrent.{ExecutionContext, Future}

object OrderConsumer {
  private final case class ConsumerConf(bootstrapServers: String,
                                        groupId: String,
                                        commandTopic: String)

  def run(system: ActorSystem[?],
          coordinatorProvider: String => EntityRef[Coordinator.Command],
          dedup: OrderDedup): Unit = {
    given ActorSystem[?] = system
    given ExecutionContext = system.executionContext

    val cfg = readConsumerConf(system.settings.config)

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

          dedup.alreadyProcessed(dto.tag).flatMap {
            case true =>
              Future.successful(msg.committableOffset)
            case false =>
              coordinatorProvider(dto.elevatorName) ! AddOriginalStream(List(dto))
              dedup.markProcessed(dto.tag).map(_ => msg.committableOffset)
          }
        }

    val (killSwitch, done) =
      Consumer
        .committableSource(consumerSettings, Subscriptions.topics(cfg.commandTopic))
        .viaMat(KillSwitches.single)(Keep.right)
        .via(toCoordinator)
        .toMat(Committer.sink(committerSettings))(Keep.both)
        .run()

    done.failed.foreach(ex => system.log.error("Kafka stream failed", ex))

    // Drain on graceful shutdown (SIGTERM during a rolling restart): in PhaseServiceUnbind —
    // which runs BEFORE the cluster member leaves and shards hand off — stop pulling new orders
    // and let in-flight ones finish processing and commit their offsets. Uncommitted orders
    // simply redeliver to the surviving pod (at-least-once + dedup), so no order is lost.
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseServiceUnbind,
      "stop-order-consumer"
    ) { () =>
      killSwitch.shutdown()
      done.map(_ => Done).recover { case _ => Done }
    }
  }

  private def readConsumerConf(root: Config): ConsumerConf = {
    val cfg = root.getConfig("elevator.kafka")

    ConsumerConf(
      bootstrapServers = cfg.getString("bootstrap-servers"),
      groupId = cfg.getString("group-id"),
      commandTopic = cfg.getString("command-topic")
    )
  }
}
