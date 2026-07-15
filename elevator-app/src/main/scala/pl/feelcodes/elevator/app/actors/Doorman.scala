package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.core.domain.{DoorState, Floor}
import pl.feelcodes.elevator.common.protocol.DoormanProtocol

import scala.concurrent.duration.FiniteDuration

/** Holds the door open at a floor and waits for the passenger to step in — an external action —
  * instead of blindly sleeping a fixed dwell. A `Boarded` closes the door at once; a no-show is
  * bounded by `boardTimeout`. Either outcome publishes `Closed`, which the wiring loops back to the
  * Controller as `DoorClosed` to resume movement. The wait is a timer-backed state, so the actor
  * never blocks its thread and stays free to receive the boarding message. */
object Doorman:
  export DoormanProtocol.*

  type PublishDoor = (String, Floor, DoorState) => Unit

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Doorman")

  private def timerKey(elevatorName: String, floor: Floor): String = s"$elevatorName@${floor.num}"

  def apply(publishDoor: PublishDoor, boardTimeout: FiniteDuration): Behavior[Command] =
    Behaviors.withTimers { timers =>

      def idle: Behavior[Command] =
        Behaviors.receive { (context, msg) =>
          msg match
            case Serve(elevatorName, floor) =>
              publishDoor(elevatorName, floor, DoorState.Open)
              timers.startSingleTimer(timerKey(elevatorName, floor), BoardTimeout(elevatorName, floor), boardTimeout)
              context.log.info(s" [$elevatorName] door @ ${floor.num} open — waiting for boarding")
              boarding(elevatorName, floor)
            case _ =>
              Behaviors.same
        }

      def boarding(elevatorName: String, floor: Floor): Behavior[Command] =
        Behaviors.receive { (context, msg) =>
          msg match
            case Boarded(n, f, passengerId) if n == elevatorName && f == floor =>
              timers.cancel(timerKey(elevatorName, floor))
              publishDoor(elevatorName, floor, DoorState.Closed)
              context.log.info(s" [$elevatorName] $passengerId boarded @ ${floor.num} — door close")
              idle
            case BoardTimeout(n, f) if n == elevatorName && f == floor =>
              publishDoor(elevatorName, floor, DoorState.Closed)
              context.log.info(s" [$elevatorName] no-show @ ${floor.num} — door close on timeout")
              idle
            case _ =>
              Behaviors.same
        }

      idle
    }
