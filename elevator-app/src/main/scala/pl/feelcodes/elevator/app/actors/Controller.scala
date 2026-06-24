package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.protocol.ControllerProtocol
import pl.feelcodes.elevator.common.strategy.NextFloorStrategy

object Controller:
  export ControllerProtocol.*

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  sealed trait Event
  final case class OrderAdded(order: ElevatorOrder) extends Event
  final case class WaitingSet(waiting: Boolean) extends Event
  final case class ElevatorStateUpdated(state: ElevatorState) extends Event

  final case class State(waiting: Boolean,
                         elevatorName: ElevatorName,
                         elevatorState: ElevatorState,
                         orders: Set[ElevatorOrder])

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command],
            coordinatorProvider: (elevatorName: String) => EntityRef[Coordinator.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.setup { context =>

      def sendNext(s: State, orders: Set[ElevatorOrder]): Unit =
        if orders.nonEmpty then
          val command = NextFloorStrategy.chooseNextAndBuildCommand(
            s.elevatorState.floor, s.elevatorState.direction, orders.map(_.floor))
          operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, command)
        else if s.elevatorState.motion == Motion.Moving then
          operatorProvider(s.elevatorName) ! Operator.Stop(s.elevatorName, s.elevatorState)

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
        emptyState = State(
          waiting = false,
          elevatorName = elevatorName,
          elevatorState = ElevatorState(Direction.Up, Motion.Stopped, Floor(0)),
          orders = Set.empty
        ),
        commandHandler = (state, msg) =>
          msg match
            case AddOrder(order) =>
              val events =
                if state.orders.exists(_.tag == order.tag) then Nil
                else List(OrderAdded(order))
              Effect.persist(events).thenRun(s => context.self ! ChooseNextOrder(s.orders))

            case PublishState(newState) =>
              val servedHere = state.orders.exists(_.floor == newState.floor)
              Effect.persist(WaitingSet(false), ElevatorStateUpdated(newState)).thenRun { s =>
                publish(ElevatorStateDto("", s.elevatorName,
                  newState.direction.toString, newState.motion.toString, newState.floor.num))
                if servedHere then
                  coordinatorProvider(s.elevatorName) ! Coordinator.Reached(newState.floor.num)
                context.self ! ChooseNextOrder(s.orders)
              }

            case ChooseNextOrder(orders) =>
              val act = !state.waiting && (orders.nonEmpty || state.elevatorState.motion == Motion.Moving)
              if act then Effect.persist(WaitingSet(true)).thenRun(s => sendNext(s, orders))
              else Effect.none
        ,
        eventHandler = (state, event) =>
          event match
            case OrderAdded(order) =>
              state.copy(orders = state.orders + order)
            case WaitingSet(waiting) =>
              state.copy(waiting = waiting)
            case ElevatorStateUpdated(newState) =>
              state.copy(
                elevatorState = newState,
                orders = state.orders.filterNot(_.floor == newState.floor))
      ).receiveSignal {
        case (state, RecoveryCompleted) if state.waiting =>
          sendNext(state, state.orders)
        case (state, RecoveryCompleted) if state.orders.nonEmpty || state.elevatorState.motion == Motion.Moving =>
          context.self ! ChooseNextOrder(state.orders)
      }.withTagger {
        case _: ElevatorStateUpdated => Set("controller-state")
        case _ => Set.empty
      }
    }
