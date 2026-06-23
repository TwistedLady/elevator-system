package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext}
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.core.*

/** A dumb worker: it executes exactly the command the Controller hands it and reports the result.
  * It does NOT publish state and does NOT decide whether to move or stop — that's the Controller's
  * job. It only carries plain data (name + state + command), never the Elevator/Engine, so the
  * messages serialize cleanly across nodes.
  *
  * The only thing that varies between operators is which engine builds the elevator: that's the
  * `build` template method, overridden by [[FastOperator]] / [[SlowOperator]]. The concrete class
  * is chosen at startup from config key `elevator.operator-class` — see the inline match in
  * [[pl.feelcodes.elevator.app.ElevatorApp]]. */
abstract class Operator(context: ActorContext[Operator.Command],
                        reportMove: Operator.ReportMove,
                        reportStop: Operator.ReportStop)
    extends AbstractBehavior[Operator.Command](context):
  import Operator.*

  /** The varying step: build an elevator (with this operator's engine) at the given state. */
  protected def build(name: String, state: ElevatorState): Elevator

  override def onMessage(msg: Command): Behavior[Command] =
    msg match
      case Move(elevatorName, state, orderWithCommand) =>
        val moved = build(elevatorName, state).move(orderWithCommand.command)
        val newState = ElevatorState(moved.direction(), moved.motion(), moved.floor())
        reportMove(elevatorName, newState, orderWithCommand)
        // Log only when the car actually moves a floor (a parked car still gets Move ticks).
        if newState.floor.num != state.floor.num then
          context.log.info(s" [$elevatorName] ${state.floor.num} >>> ${newState.floor.num}  (target ${orderWithCommand.order.floor.num})")
        this

      case Stop(elevatorName, state) =>
        val stopped = build(elevatorName, state).stop()
        val newState = ElevatorState(stopped.direction(), stopped.motion(), stopped.floor())
        reportStop(elevatorName, newState)
        this

/** Cheap engine: ~instant moves. */
final class FastOperator(context: ActorContext[Operator.Command],
                         reportMove: Operator.ReportMove,
                         reportStop: Operator.ReportStop)
    extends Operator(context, reportMove, reportStop):
  override protected def build(name: String, state: ElevatorState): Elevator =
    Elevator.fast(name)(state)

/** Heavy engine: each move burns a lot of CPU (the SlowEngine cost). */
final class SlowOperator(context: ActorContext[Operator.Command],
                         reportMove: Operator.ReportMove,
                         reportStop: Operator.ReportStop)
    extends Operator(context, reportMove, reportStop):
  override protected def build(name: String, state: ElevatorState): Elevator =
    Elevator.slow(name)(state)

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
  type ReportMove = (String, ElevatorState, OrderElevatorCommand) => Unit
  type ReportStop = (String, ElevatorState) => Unit
}
