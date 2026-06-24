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
import pl.feelcodes.elevator.app.actors.Coordinator
import pl.feelcodes.elevator.common.events.CoordinatorEvents

import scala.concurrent.{ExecutionContext, Future}

object OrderStatusProjection {

  private val NumberOfInstances = 4

  private val UpsertAcceptedSql =
    """INSERT INTO order_status (tag, elevator_name, floor, status, created_at)
      |VALUES ($1, $2, $3, 'PROGRESS', now())
      |ON CONFLICT (tag) DO UPDATE SET
      |  elevator_name = $2,
      |  floor         = $3""".stripMargin

  def init(system: ActorSystem[?]): Unit = {
    val ranges = EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, NumberOfInstances)

    ShardedDaemonProcess(system).init(
      name = "order-status-projection",
      numberOfInstances = ranges.size,
      behaviorFactory = i => ProjectionBehavior(projection(system, ranges(i).min, ranges(i).max)),
      settings = ShardedDaemonProcessSettings(system).withRole(ElevatorStateProjection.ReadModelRole),
      stopMessage = Some(ProjectionBehavior.Stop)
    )
  }

  private def projection(system: ActorSystem[?], minSlice: Int, maxSlice: Int) = {
    given ActorSystem[?] = system

    val sourceProvider: SourceProvider[Offset, EventEnvelope[CoordinatorEvents.Event]] =
      EventSourcedProvider.eventsBySlices[CoordinatorEvents.Event](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        entityType = Coordinator.TypeKey.name,
        minSlice = minSlice,
        maxSlice = maxSlice
      )

    R2dbcProjection.exactlyOnce(
      projectionId = ProjectionId("order-status", s"$minSlice-$maxSlice"),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () => new Handler()
    )
  }

  private final class Handler extends R2dbcHandler[EventEnvelope[CoordinatorEvents.Event]] {
    override def process(session: R2dbcSession, envelope: EventEnvelope[CoordinatorEvents.Event]): Future[Done] =
      envelope.event match {
        case CoordinatorEvents.OrderAccepted(tag, elevatorName, floor) =>
          val statement = session
            .createStatement(UpsertAcceptedSql)
            .bind(0, tag)
            .bind(1, elevatorName)
            .bind(2, floor)
          session.updateOne(statement).map(_ => Done)(ExecutionContext.parasitic)
      }
  }
}
