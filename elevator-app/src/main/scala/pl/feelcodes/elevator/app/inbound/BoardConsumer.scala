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
import pl.feelcodes.elevator.app.actors.Doorman
import pl.feelcodes.elevator.common.core.domain.Floor
import pl.feelcodes.elevator.common.dto.BoardDto
import pl.feelcodes.elevator.common.serializable.Json

import scala.concurrent.ExecutionContext

/** Reads boarding signals (a passenger stepping in) from Kafka and delivers each to its elevator's
  * Doorman as `Boarded`. Boarding is a live signal that only matters while a door is open, so it
  * starts at `latest` (no replay) and needs no dedup — a stale or duplicate Boarded is ignored by
  * the Doorman. Routing to the sharded Doorman means any replica can forward it. */
object BoardConsumer {
  private final case class ConsumerConf(bootstrapServers: String, groupId: String, boardTopic: String)

  /** Decode a board record into the message the Doorman waits for. */
  def decode(value: Array[Byte]): Doorman.Boarded = {
    val dto = Json.decode(value, classOf[BoardDto])
    Doorman.Boarded(dto.elevatorName, Floor(dto.floor), dto.passengerId)
  }

  def run(system: ActorSystem[?],
          doormanProvider: String => EntityRef[Doorman.Command]): Unit = {
    given ActorSystem[?] = system
    given ExecutionContext = system.executionContext

    val cfg = readConsumerConf(system.settings.config)

    val consumerSettings =
      ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
        .withBootstrapServers(cfg.bootstrapServers)
        .withGroupId(cfg.groupId)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

    val committerSettings = CommitterSettings(system)

    val toDoorman: Flow[CommittableMessage[String, Array[Byte]], CommittableOffset, ?] =
      Flow[CommittableMessage[String, Array[Byte]]]
        .map { msg =>
          val boarded = decode(msg.record.value())
          doormanProvider(boarded.elevatorName) ! boarded
          system.log.info("[board] {} @ floor {} by {}", boarded.elevatorName, boarded.floor.num, boarded.passengerId)
          msg.committableOffset
        }

    val (killSwitch, done) =
      Consumer
        .committableSource(consumerSettings, Subscriptions.topics(cfg.boardTopic))
        .viaMat(KillSwitches.single)(Keep.right)
        .via(toDoorman)
        .toMat(Committer.sink(committerSettings))(Keep.both)
        .run()

    done.failed.foreach(ex => system.log.error("Board Kafka stream failed", ex))

    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseServiceUnbind,
      "stop-board-consumer"
    ) { () =>
      killSwitch.shutdown()
      done.map(_ => Done).recover { case _ => Done }
    }
  }

  private def readConsumerConf(root: Config): ConsumerConf = {
    val cfg = root.getConfig("elevator.kafka")
    ConsumerConf(
      bootstrapServers = cfg.getString("bootstrap-servers"),
      groupId = cfg.getString("group-id") + "-board",
      boardTopic = cfg.getString("board-topic")
    )
  }
}
