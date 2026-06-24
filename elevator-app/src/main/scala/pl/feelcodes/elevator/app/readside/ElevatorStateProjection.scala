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

/** Read-side (CQRS) of the elevator system — a *consumer* of the write-side journal, not part
  * of it. Lives in its own `readside` package to make the read/write split explicit.
  *
  * It streams `Controller` events back out of the R2DBC journal by *slice*: Pekko hashes every
  * persistenceId into one of 1024 slices, and we consume slice ranges. `ShardedDaemonProcess`
  * runs a few projection instances and balances them across the cluster — pinned to nodes with
  * the `read-model` role (see [[ReadModelRole]]), so the read side can scale independently of the
  * write side once there is more than one node.
  *
  * For each `ElevatorStateUpdated` we upsert the elevator's current state into `elevator_state_view`.
  * The projection's read position (offset) is stored in the SAME Postgres transaction as that
  * upsert (`exactlyOnce`), so a restart resumes exactly where it left off — no gaps, no duplicates.
  */
object ElevatorStateProjection {

  /** Cluster role this projection runs on. The single-node app must carry this role
    * (see application.conf `pekko.cluster.roles`); multi-node setups put it on read-side nodes. */
  val ReadModelRole = "read-model"

  // Run the read-model writes across this many parallel projection instances.
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

  /** Wire the projection into the running system. Call once from the composition root. */
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
        entityType = Controller.TypeKey.name, // "Controller"
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

  /** The per-event handler. `process` runs inside the projection's DB transaction. */
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

        // RequestAdded / WaitingSet don't change the visible state — skip them.
        case _ => Future.successful(Done)
      }
  }

  /** persistenceId is "Controller|<elevatorName>"; we want the entity id after the separator. */
  private def entityId(persistenceId: String): String =
    persistenceId.split("\\|", 2) match {
      case Array(_, id) => id
      case _            => persistenceId
    }
}
