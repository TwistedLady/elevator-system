package pl.feelcodes.elevator.app.readside

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.ShardedDaemonProcessSettings
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcProjection, R2dbcSession}
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.{ProjectionBehavior, ProjectionId}
import pl.feelcodes.elevator.app.actors.Controller

import scala.concurrent.{ExecutionContext, Future}

object ElevatorStateProjection {

  val ReadModelRole = "read-model"

  private val NumberOfInstances = 4

  private val UpsertSql =
    """INSERT INTO elevator_state_view
      |  (elevator_name, floor, direction, motion, last_order_tag, updated_at)
      |VALUES ($1, $2, $3, $4, $5, now())
      |ON CONFLICT (elevator_name) DO UPDATE SET
      |  floor          = $2,
      |  direction      = $3,
      |  motion         = $4,
      |  last_order_tag = $5,
      |  updated_at     = now()""".stripMargin

  def init(system: ActorSystem[?]): Unit = {
    val ranges = EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, NumberOfInstances)

    ShardedDaemonProcess(system).init(
      name = "elevator-state-projection",
      numberOfInstances = ranges.size,
      behaviorFactory = i => ProjectionBehavior(projection(system, ranges(i).min, ranges(i).max)),
      settings = ShardedDaemonProcessSettings(system).withRole(ReadModelRole),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }

  private def projection(system: ActorSystem[?], minSlice: Int, maxSlice: Int) = {
    given ActorSystem[?] = system

    val sourceProvider: SourceProvider[Offset, EventEnvelope[Controller.Event]] =
      EventSourcedProvider.eventsBySlices[Controller.Event](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        entityType = Controller.TypeKey.name,
        minSlice = minSlice,
        maxSlice = maxSlice
      )

    R2dbcProjection.exactlyOnce(
      projectionId = ProjectionId("elevator-state", s"$minSlice-$maxSlice"),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () => new Handler()
    )
  }

  private final class Handler extends R2dbcHandler[EventEnvelope[Controller.Event]] {
    override def process(session: R2dbcSession, envelope: EventEnvelope[Controller.Event]): Future[Done] =
      envelope.event match {
        case Controller.ElevatorStateUpdated(state) =>
          val statement = session
            .createStatement(UpsertSql)
            .bind(0, entityId(envelope.persistenceId))
            .bind(1, state.floor.num)
            .bind(2, state.direction.toString)
            .bind(3, state.motion.toString)
            .bind(4, "")
          session.updateOne(statement).map(_ => Done)(ExecutionContext.parasitic)

        case _ => Future.successful(Done)
      }
  }

  private def entityId(persistenceId: String): String =
    persistenceId.split("\\|", 2) match {
      case Array(_, id) => id
      case _            => persistenceId
    }
}
