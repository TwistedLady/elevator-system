package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.protocol.{ControllerProtocol, ControllerDecider}
object Controller:
  export ControllerProtocol.*
  export ControllerDecider.*

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command],
            coordinatorProvider: (elevatorName: String) => EntityRef[Coordinator.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.setup { context =>

      def sendNext(s: State, orders: Set[ElevatorOrder]): Unit =
        if orders.nonEmpty then
          val command = Policy.next(s.elevatorState.floor, s.elevatorState.direction, orders)
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
          val events = ControllerDecider.decide(state, msg)
          msg match
            case AddOrder(_) =>
              Effect.persist(events).thenRun(s => context.self ! ChooseNextOrder(s.orders))

            case PublishState(newState) =>
              val servedHere = state.orders.exists(_.floor == newState.floor)
              Effect.persist(events).thenRun { s =>
                publish(ElevatorStateDto("", s.elevatorName,
                  newState.direction.toString, newState.motion.toString, newState.floor.num))
                if servedHere then
                  coordinatorProvider(s.elevatorName) ! Coordinator.Reached(newState.floor.num)
                context.self ! ChooseNextOrder(s.orders)
              }

            case ChooseNextOrder(orders) =>
              if events.isEmpty then Effect.none
              else Effect.persist(events).thenRun(s => sendNext(s, orders))
        ,
        eventHandler = ControllerDecider.evolve
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
