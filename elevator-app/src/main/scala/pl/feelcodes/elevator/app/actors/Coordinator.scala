package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import pl.feelcodes.elevator.common.core.domain.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol
import pl.feelcodes.elevator.common.events.CoordinatorEvents
import pl.feelcodes.elevator.common.events.CoordinatorEvents.{OrderAccepted, OrderDone}
import pl.feelcodes.elevator.common.logic.CoordinatorLogic

object Coordinator:
  export CoordinatorProtocol.*

  type State = CoordinatorLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  def apply(elevatorName: String,
            controllerProvider: String => EntityRef[Controller.Command]): Behavior[Command] =
    EventSourcedBehavior[Command, CoordinatorEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = CoordinatorLogic.State.empty,
      commandHandler = (_, msg) =>
        msg match
          case AddOriginalStream(orders) =>
            val merged = CoordinatorLogic.mergeByFloor(orders.map(o => ElevatorOrder(o.tag, Floor(o.floor))))
            Effect.persist(orders.map(o => OrderAccepted(o.tag, o.elevatorName, o.floor)))
              .thenRun(_ => controllerProvider(elevatorName) ! Controller.AddUniqueOrderSet(merged))

          case MarkOrderDone(tag) =>
            Effect.persist(OrderDone(tag))
      ,
      eventHandler = (state, _) => state
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
