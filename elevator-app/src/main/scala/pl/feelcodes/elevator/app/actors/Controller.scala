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
            doormanProvider: String => EntityRef[Doorman.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>

      given Timeout = SuspendDwell.duration + 2.seconds
      val SuspendRevealDelay = 500.millis

      def requestMove(s: State): Unit =
        timers.startSingleTimer("suspend-reveal", RevealSuspended, SuspendRevealDelay)
        context.ask(suspendManager, SuspendManager.MayMove(s.elevatorName, s.elevatorState, _)) {
          case Success(SuspendManager.Decision(allowed)) => MoveDecision(allowed)
          case Failure(_)                                => MoveRetry
        }

      def issueMove(s: State): Unit =
        ControllerLogic.nextCommand(s, s.orders).foreach { command =>
          operatorProvider(s.elevatorName) ! Operator.Move(s.elevatorName, s.elevatorState, command)
        }

      def publishState(s: State, suspended: Boolean): Unit =
        publish(ElevatorStateDto(s.elevatorName,
          s.elevatorState.direction.toString, s.elevatorState.motion.toString,
          s.elevatorState.floor.num, suspended))

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
              Effect.persist(ControllerLogic.arrival(newState, served.nonEmpty)).thenRun { s =>
                publish(ElevatorStateDto(s.elevatorName,
                  newState.direction.toString, newState.motion.toString, newState.floor.num))
                suspendManager ! SuspendManager.Arrived(s.elevatorName, newState.floor)
                served.foreach(o => managerProvider(s.elevatorName) ! Manager.MarkDone(o.id))
                if served.nonEmpty then doormanProvider(s.elevatorName) ! Doorman.Serve(s.elevatorName, newState.floor)
                else context.self ! ChooseNext(s.orders)
              }

            case DoorClosed(_) =>
              Effect.persist(WaitingSet(false)).thenRun(s => context.self ! ChooseNext(s.orders))

            case ChooseNext(orders) =>
              if ControllerLogic.shouldAct(state, orders) then
                Effect.persist(WaitingSet(true)).thenRun(s => requestMove(s))
              else Effect.none

            case RevealSuspended =>
              Effect.none.thenRun(s => if s.waiting then publishState(s, suspended = true))

            case MoveDecision(allowed) =>
              timers.cancel("suspend-reveal")
              if allowed then Effect.none.thenRun(s => issueMove(s))
              else Effect.persist(WaitingSet(false)).thenRun(s => publishState(s, suspended = false))

            case MoveRetry =>
              timers.cancel("suspend-reveal")
              Effect.persist(WaitingSet(false)).thenRun(s => context.self ! ChooseNext(s.orders))
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
    }
