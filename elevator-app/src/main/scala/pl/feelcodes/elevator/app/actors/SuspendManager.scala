package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import pl.feelcodes.elevator.common.core.domain.ElevatorState
import pl.feelcodes.elevator.common.strategy.SuspendStrategy

object SuspendManager:
  sealed trait Command
  final case class MayMove(state: ElevatorState, replyTo: ActorRef[Decision]) extends Command

  final case class Decision(allowed: Boolean)

  def apply(strategy: SuspendStrategy = SuspendStrategy.default): Behavior[Command] =
    Behaviors.receiveMessage {
      case MayMove(state, replyTo) =>
        replyTo ! Decision(strategy.mayMove(state))
        Behaviors.same
    }
