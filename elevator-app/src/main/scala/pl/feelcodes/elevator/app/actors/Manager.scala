package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import pl.feelcodes.elevator.common.core.domain.{Floor, Order}
import pl.feelcodes.elevator.common.dto.OrderStateDto
import pl.feelcodes.elevator.common.protocol.ManagerProtocol
import pl.feelcodes.elevator.common.events.ManagerEvents
import pl.feelcodes.elevator.common.events.ManagerEvents.{OrderCreated, OrderDone}
import pl.feelcodes.elevator.common.logic.ManagerLogic

/** Owns the call↔order relation: groups calls into orders, assigns them, marks orders done. */
object Manager:
  export ManagerProtocol.*

  type State = ManagerLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Manager")

  def apply(elevatorName: String,
            coordinatorProvider: String => EntityRef[Coordinator.Command],
            controllerProvider: String => EntityRef[Controller.Command],
            publish: OrderStateDto => Unit): Behavior[Command] =
    EventSourcedBehavior[Command, ManagerEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = ManagerLogic.State.empty,
      commandHandler = (state, msg) =>
        msg match
          case Combine(calls) =>
            val created = ManagerLogic.created(state, ManagerLogic.combine(calls))
            val orders = created.map(e => Order(e.orderId, Floor(e.floor), e.callIds)).toSet
            Effect.persist(created).thenRun { _ =>
              orders.foreach { o =>
                publish(OrderStateDto(o.id, elevatorName, o.floor.num, "PROGRESS", o.callIds))
                o.callIds.foreach(callId => coordinatorProvider(elevatorName) ! Coordinator.AssignOrder(callId, o.id))
              }
              if orders.nonEmpty then controllerProvider(elevatorName) ! Controller.Process(orders)
            }

          case MarkOrderDone(orderId) =>
            state.orders.get(orderId) match
              case Some(order) =>
                Effect.persist(OrderDone(orderId)).thenRun { _ =>
                  publish(OrderStateDto(order.id, elevatorName, order.floor.num, "DONE", order.callIds))
                  order.callIds.foreach(callId => coordinatorProvider(elevatorName) ! Coordinator.MarkCallDone(callId))
                }
              case None => Effect.none
      ,
      eventHandler = ManagerLogic.evolve
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
