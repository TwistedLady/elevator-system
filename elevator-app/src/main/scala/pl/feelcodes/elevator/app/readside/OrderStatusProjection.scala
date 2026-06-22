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

import scala.concurrent.{ExecutionContext, Future}

/** Read-side (CQRS) order-status view, keyed by order tag. A consumer of the write-side journal,
  * it streams the `Coordinator`'s events by slice and maintains `order_status`:
  *   - `Accepted`  -> upsert the row with status `PROGRESS` (created_at set)
  *   - `Completed` -> flip the row to `DONE` (done_at set)
  *
  * The Coordinator is the source of truth for both intake (dedup) and completion, so this view
  * answers "was the order with tag X processed?" consistently with that. Like
  * [[ElevatorStateProjection]] it runs as a `ShardedDaemonProcess` pinned to the `read-model` role,
  * with the read offset committed in the same transaction as the upsert (`exactlyOnce`).
  */
object OrderStatusProjection {

  private val NumberOfInstances = 4

  private val UpsertAcceptedSql =
    """INSERT INTO order_status (tag, elevator_name, floor, status, created_at)
      |VALUES ($1, $2, $3, 'PROGRESS', now())
      |ON CONFLICT (tag) DO UPDATE SET
      |  elevator_name = $2,
      |  floor         = $3""".stripMargin

  private val MarkDoneSql =
    "UPDATE order_status SET status = 'DONE', done_at = now() WHERE tag = $1"

  /** Wire the projection into the running system. Call once from the composition root. */
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

    val sourceProvider: SourceProvider[Offset, EventEnvelope[Coordinator.Event]] =
      EventSourcedProvider.eventsBySlices[Coordinator.Event](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        entityType = Coordinator.TypeKey.name, // "Coordinator"
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

  /** The per-event handler. `process` runs inside the projection's DB transaction. */
  private final class Handler extends R2dbcHandler[EventEnvelope[Coordinator.Event]] {
    override def process(session: R2dbcSession, envelope: EventEnvelope[Coordinator.Event]): Future[Done] =
      envelope.event match {
        case Coordinator.Accepted(tag, elevatorName, floor) =>
          val statement = session
            .createStatement(UpsertAcceptedSql)
            .bind(0, tag)
            .bind(1, elevatorName)
            .bind(2, floor)
          session.updateOne(statement).map(_ => Done)(ExecutionContext.parasitic)

        case Coordinator.Completed(tag) =>
          val statement = session.createStatement(MarkDoneSql).bind(0, tag)
          session.updateOne(statement).map(_ => Done)(ExecutionContext.parasitic)
      }
  }
}
