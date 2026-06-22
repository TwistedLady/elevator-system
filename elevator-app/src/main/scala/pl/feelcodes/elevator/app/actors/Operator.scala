package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.core.*

/** A dumb worker: it executes exactly the command the Controller hands it and reports the result.
  * It does NOT publish state and does NOT decide whether to move or stop — that's the Controller's
  * job. It only carries plain data (name + state + command), never the Elevator/Engine, so the
  * messages serialize cleanly across nodes. */
object Operator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Operator")

  sealed trait Command

  /** Run one move toward an order (the order carries the Go command). */
  final case class Move(elevatorName: String,
                        state: ElevatorState,
                        orderWithCommand: OrderElevatorCommand) extends Command

  /** Stop the car. The Operator has the ability to stop but never decides to — the Controller
    * sends this when it has no more requests. */
  final case class Stop(elevatorName: String, state: ElevatorState) extends Command

  /** Two narrow report ports back to the Controller (its only collaborator):
    *   - `reportMove`: the new state after a move (carries the order it was serving)
    *   - `reportStop`: the new (stopped) state */
  def apply(reportMove: (String, ElevatorState, OrderElevatorCommand) => Unit,
            reportStop: (String, ElevatorState) => Unit): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case Move(elevatorName, state, orderWithCommand) =>
          val moved = Elevator.fast(elevatorName)(state).move(orderWithCommand.command)
          val newState = ElevatorState(moved.direction(), moved.motion(), moved.floor())
          reportMove(elevatorName, newState, orderWithCommand)
          // Log only when the car actually moves a floor (a parked car still gets Move ticks).
          if newState.floor.num != state.floor.num then
            ctx.log.info(s" [$elevatorName] ${state.floor.num} >>> ${newState.floor.num}  (target ${orderWithCommand.order.floor.num})")
          Behaviors.same

        case Stop(elevatorName, state) =>
          val stopped = Elevator.fast(elevatorName)(state).stop()
          val newState = ElevatorState(stopped.direction(), stopped.motion(), stopped.floor())
          reportStop(elevatorName, newState)
          Behaviors.same
    }
}
