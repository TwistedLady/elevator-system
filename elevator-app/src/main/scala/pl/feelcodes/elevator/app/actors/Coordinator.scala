package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol
import pl.feelcodes.elevator.common.events.CoordinatorEvents
import pl.feelcodes.elevator.common.events.CoordinatorEvents.{OrderAccepted, OrderDone}
import pl.feelcodes.elevator.common.strategy.CoordinatorStrategy

object Coordinator:
  export CoordinatorProtocol.*

  type State = CoordinatorStrategy.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  def apply(elevatorName: String,
            controllerProvider: String => EntityRef[Controller.Command]): Behavior[Command] =
    EventSourcedBehavior[Command, CoordinatorEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = CoordinatorStrategy.State.empty,
      commandHandler = (_, msg) =>
        msg match
          case AddOriginalStream(orders) =>
            val merged = CoordinatorStrategy.mergeByFloor(orders.map(o => ElevatorOrder(o.tag, Floor(o.floor))))
            Effect.persist(orders.map(o => OrderAccepted(o.tag, o.elevatorName, o.floor)))
              .thenRun(_ => controllerProvider(elevatorName) ! Controller.AddUniqueOrderSet(merged))

          case MarkOrderDone(tag) =>
            Effect.persist(OrderDone(tag))
      ,
      eventHandler = (state, _) => state
    )
