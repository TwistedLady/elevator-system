package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import pl.feelcodes.elevator.common.dto.OrderStateDto
import pl.feelcodes.elevator.common.protocol.ManagerProtocol
import pl.feelcodes.elevator.common.events.ManagerEvents
import pl.feelcodes.elevator.common.events.ManagerEvents.{OrderCreated, OrderExtended, OrderDone}
import pl.feelcodes.elevator.common.logic.ManagerLogic

/** Owns the call↔order relation: attaches calls to one order per floor, assigns them, marks orders done. */
object Manager:
  export ManagerProtocol.*

  type State = ManagerLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Manager")

  def apply(elevatorName: String,
            coordinatorProvider: String => EntityRef[Coordinator.Command],
            controllerProvider: String => EntityRef[Controller.Command],
            passengerManagerProvider: String => EntityRef[PassengerManager.Command],
            publish: OrderStateDto => Unit): Behavior[Command] =
    EventSourcedBehavior[Command, ManagerEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = ManagerLogic.State.empty,
      commandHandler = (state, msg) =>
        msg match
          case Combine(calls) =>
            val events = ManagerLogic.plan(state, ManagerLogic.combine(elevatorName, calls))
            Effect.persist(events).thenRun { newState =>
              events.foreach {
                case OrderCreated(id, _, callIds, _, _) =>
                  newState.orders.get(id).foreach { o =>
                    publish(OrderStateDto(o.id, elevatorName, o.floor.num, "PROGRESS", o.callIds, o.passengerCount, o.anonymousCount))
                    callIds.foreach(callId => coordinatorProvider(elevatorName) ! Coordinator.AssignOrder(callId, id))
                    controllerProvider(elevatorName) ! Controller.Process(Set(o))
                  }
                case OrderExtended(id, callIds, _, _) =>
                  newState.orders.get(id).foreach { o =>
                    publish(OrderStateDto(o.id, elevatorName, o.floor.num, "PROGRESS", o.callIds, o.passengerCount, o.anonymousCount))
                    callIds.foreach(callId => coordinatorProvider(elevatorName) ! Coordinator.AssignOrder(callId, id))
                    controllerProvider(elevatorName) ! Controller.Process(Set(o))
                  }
              }
            }

          case MarkDone(orderId) =>
            state.orders.get(orderId) match
              case Some(order) =>
                Effect.persist(OrderDone(orderId)).thenRun { _ =>
                  publish(OrderStateDto(order.id, elevatorName, order.floor.num, "DONE", order.callIds, order.passengerCount, order.anonymousCount))
                  order.callIds.foreach(callId => coordinatorProvider(elevatorName) ! Coordinator.MarkDone(callId))
                  order.passengers.foreach(pid => passengerManagerProvider(pid) ! PassengerManager.Free(pid))
                }
              case None => Effect.none
      ,
      eventHandler = ManagerLogic.evolve
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
