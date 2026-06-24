package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.protocol.ControllerProtocol
import pl.feelcodes.elevator.common.events.ControllerEvents
import pl.feelcodes.elevator.common.strategy.ControllerStrategy

object Controller:
  export ControllerProtocol.*
  export ControllerEvents.*

  type State = ControllerStrategy.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.setup { context =>

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
        emptyState = ControllerStrategy.State.initial(elevatorName),
        commandHandler = (state, msg) =>
          msg match
            case AddUniqueOrderSet(orders) =>
              Effect.persist(ControllerStrategy.addUniqueOrders(state, orders))
                .thenRun(s => context.self ! ChooseNextOrder(s.orders))

            case PublishState(newState) =>
              Effect.persist(ControllerStrategy.publishState(newState)).thenRun { s =>
                publish(ElevatorStateDto("", s.elevatorName,
                  newState.direction.toString, newState.motion.toString, newState.floor.num))
                context.self ! ChooseNextOrder(s.orders)
              }

            case ChooseNextOrder(orders) =>
              if ControllerStrategy.shouldAct(state, orders) then
                Effect.persist(WaitingSet(true)).thenRun { s =>
                  ControllerStrategy.nextCommand(s, orders).foreach { command =>
                    operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, command)
                  }
                }
              else Effect.none
        ,
        eventHandler = ControllerStrategy.evolve
      ).receiveSignal {
        case (state, RecoveryCompleted) if state.waiting =>
          ControllerStrategy.nextCommand(state, state.orders).foreach { command =>
            operatorProvider(state.elevatorName) ! Operator.Move(state.elevatorName, state.elevatorState, command)
          }
        case (state, RecoveryCompleted) if state.orders.nonEmpty || state.elevatorState.motion == Motion.Moving =>
          context.self ! ChooseNextOrder(state.orders)
      }.withTagger {
        case _: ElevatorStateUpdated => Set("controller-state")
        case _ => Set.empty
      }
    }
