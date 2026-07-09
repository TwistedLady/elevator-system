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
import pl.feelcodes.elevator.common.core.domain.{Call, Floor}
import pl.feelcodes.elevator.common.dto.CallDto
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol.Handle
import pl.feelcodes.elevator.common.serializable.Json

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters.*

/** Reads calls from Kafka in batches, dedups by call id, and hands each elevator its calls. */
object CallConsumer {
  private final case class ConsumerConf(bootstrapServers: String,
                                        groupId: String,
                                        callTopic: String,
                                        batchMaxSize: Int,
                                        batchInterval: FiniteDuration)

  def run(system: ActorSystem[?],
          coordinatorProvider: String => EntityRef[Coordinator.Command],
          dedup: CallDedup): Unit = {
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
        .groupedWithin(cfg.batchMaxSize, cfg.batchInterval)
        .mapAsync(1) { msgs =>
          val decoded = msgs.map(msg => Json.decode(msg.record.value(), classOf[CallDto]))

          Future
            .traverse(decoded)(dto => dedup.alreadyProcessed(dto.id).map(seen => (dto, seen)))
            .flatMap { checked =>
              val fresh = checked.collect { case (dto, false) => dto }.distinctBy(_.id)

              fresh.groupBy(_.elevatorName).foreach { case (elevatorName, dtos) =>
                coordinatorProvider(elevatorName) ! Handle(dtos.map(d => Call(d.id, Floor(d.floor), Option(d.passengerId))).toList)
              }

              Future
                .traverse(fresh)(dto => dedup.markProcessed(dto.id))
                .map(_ => msgs.map(_.committableOffset))
            }
        }
        .mapConcat(identity)

    val (killSwitch, done) =
      Consumer
        .committableSource(consumerSettings, Subscriptions.topics(cfg.callTopic))
        .viaMat(KillSwitches.single)(Keep.right)
        .via(toCoordinator)
        .toMat(Committer.sink(committerSettings))(Keep.both)
        .run()

    done.failed.foreach(ex => system.log.error("Kafka stream failed", ex))

    // Drain on graceful shutdown (SIGTERM during a rolling restart): in PhaseServiceUnbind —
    // which runs BEFORE the cluster member leaves and shards hand off — stop pulling new calls
    // and let in-flight ones finish processing and commit their offsets. Uncommitted calls
    // simply redeliver to the surviving pod (at-least-once + dedup), so no call is lost.
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseServiceUnbind,
      "stop-call-consumer"
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
      callTopic = cfg.getString("call-topic"),
      batchMaxSize = cfg.getInt("call-batch-max-size"),
      batchInterval = cfg.getDuration("call-batch-interval").toScala
    )
  }
}
