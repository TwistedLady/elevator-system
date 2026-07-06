  package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.protocol.ControllerProtocol
import pl.feelcodes.elevator.common.events.ControllerEvents
import pl.feelcodes.elevator.common.logic.ControllerLogic

object Controller:
  export ControllerProtocol.*
  export ControllerEvents.*

  type State = ControllerLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  def apply(elevatorName: String,
            operatorProvider: (elevatorName: String) => EntityRef[Operator.Command],
            coordinatorProvider: (elevatorName: String) => EntityRef[Coordinator.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.setup { context =>

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
        emptyState = ControllerLogic.State.initial(elevatorName),
        commandHandler = (state, msg) =>
          msg match
            case AddUniqueOrderSet(orders) =>
              Effect.persist(ControllerLogic.addUniqueOrders(state, orders))
                .thenRun(s => context.self ! ChooseNextOrder(s.orders))

            case PublishState(newState) =>
              val served = state.orders.filter(_.floor == newState.floor)
              Effect.persist(ControllerLogic.publishState(newState)).thenRun { s =>
                publish(ElevatorStateDto("", s.elevatorName,
                  newState.direction.toString, newState.motion.toString, newState.floor.num))
                served.foreach(o => coordinatorProvider(s.elevatorName) ! Coordinator.MarkOrderDone(o.tag))
                context.self ! ChooseNextOrder(s.orders)
              }

            case ChooseNextOrder(orders) =>
              if ControllerLogic.shouldAct(state, orders) then
                Effect.persist(WaitingSet(true)).thenRun { s =>
                  ControllerLogic.nextCommand(s, orders).foreach { command =>
                    operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, command)
                  }
                }
              else Effect.none
        ,
        eventHandler = ControllerLogic.evolve
      ).receiveSignal {
        case (state, RecoveryCompleted) if state.waiting =>
          ControllerLogic.nextCommand(state, state.orders).foreach { command =>
            operatorProvider(state.elevatorName) ! Operator.Move(state.elevatorName, state.elevatorState, command)
          }
        case (state, RecoveryCompleted) if state.orders.nonEmpty || state.elevatorState.motion == Motion.Moving =>
          context.self ! ChooseNextOrder(state.orders)
      }.withTagger {
        case _: ElevatorStateUpdated => Set("controller-state")
        case _ => Set.empty
      }
    }
