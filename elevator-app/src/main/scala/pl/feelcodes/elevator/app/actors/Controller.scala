package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.util.Timeout
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto
import pl.feelcodes.elevator.common.protocol.ControllerProtocol
import pl.feelcodes.elevator.common.events.ControllerEvents
import pl.feelcodes.elevator.common.logic.ControllerLogic

import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Owns movement: turns orders into moves via NextFloorStrategy and marks reached orders done. */
object Controller:
  export ControllerProtocol.*
  export ControllerEvents.*

  type State = ControllerLogic.State

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Controller")

  def apply(elevatorName: String,
            operatorProvider: String => EntityRef[Operator.Command],
            managerProvider: String => EntityRef[Manager.Command],
            suspendManager: ActorRef[SuspendManager.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.setup { context =>

      given Timeout = 3.seconds

      def requestMove(s: State): Unit =
        context.ask(suspendManager, SuspendManager.MayMove(s.elevatorState, _)) {
          case Success(SuspendManager.Decision(allowed)) => MoveDecision(allowed)
          case Failure(_)                                => MoveDecision(false)
        }

      def issueMove(s: State): Unit =
        ControllerLogic.nextCommand(s, s.orders).foreach { command =>
          operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, command)
        }

      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
        emptyState = ControllerLogic.State.initial(elevatorName),
        commandHandler = (state, msg) =>
          msg match
            case Process(orders) =>
              Effect.persist(ControllerLogic.addUniqueOrders(state, orders))
                .thenRun(s => context.self ! ChooseNext(s.orders))

            case MarkExecuted(newState) =>
              val served = state.orders.filter(_.floor == newState.floor)
              Effect.persist(ControllerLogic.publishState(newState)).thenRun { s =>
                publish(ElevatorStateDto(s.elevatorName,
                  newState.direction.toString, newState.motion.toString, newState.floor.num))
                served.foreach(o => managerProvider(s.elevatorName) ! Manager.MarkDone(o.id))
                context.self ! ChooseNext(s.orders)
              }

            case ChooseNext(orders) =>
              if ControllerLogic.shouldAct(state, orders) then
                Effect.persist(WaitingSet(true)).thenRun(s => requestMove(s))
              else Effect.none

            case MoveDecision(allowed) =>
              if allowed then Effect.none.thenRun(s => issueMove(s))
              else Effect.persist(WaitingSet(false))
        ,
        eventHandler = ControllerLogic.evolve
      ).receiveSignal {
        case (state, RecoveryCompleted) if state.waiting =>
          requestMove(state)
        case (state, RecoveryCompleted) if state.orders.nonEmpty || state.elevatorState.motion == Motion.Moving =>
          context.self ! ChooseNext(state.orders)
      }.withTagger {
        case _: ElevatorStateUpdated => Set("controller-state")
        case _                       => Set.empty
      }.withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
    }
