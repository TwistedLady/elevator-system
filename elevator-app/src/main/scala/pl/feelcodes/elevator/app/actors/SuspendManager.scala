package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import pl.feelcodes.elevator.common.core.domain.{ElevatorName, ElevatorState, Floor, SuspendDwell}
import pl.feelcodes.elevator.common.strategy.SuspendStrategy

object SuspendManager:
  sealed trait Command
  final case class MayMove(elevatorName: ElevatorName, state: ElevatorState, replyTo: ActorRef[Decision]) extends Command
  final case class Arrived(elevatorName: ElevatorName, floor: Floor) extends Command
  private final case class Release(replyTo: ActorRef[Decision]) extends Command

  final case class Decision(allowed: Boolean)

  def apply(strategy: SuspendStrategy = SuspendStrategy.default): Behavior[Command] =
    Behaviors.withTimers { timers =>
      def active(positions: Map[ElevatorName, Floor]): Behavior[Command] =
        Behaviors.receiveMessage {
          case MayMove(name, state, replyTo) =>
            val floor = state.floor
            val updated = positions.updated(name, floor)
            if !strategy.mayMove(state) then
              replyTo ! Decision(false)
              active(updated)
            else if updated.exists { case (n, f) => n != name && f == floor } then
              timers.startSingleTimer(name, Release(replyTo), SuspendDwell.duration)
              active(updated)
            else
              replyTo ! Decision(true)
              active(updated)

          case Arrived(name, floor) =>
            active(positions.updated(name, floor))

          case Release(replyTo) =>
            replyTo ! Decision(true)
            Behaviors.same
        }

      active(Map.empty)
    }
