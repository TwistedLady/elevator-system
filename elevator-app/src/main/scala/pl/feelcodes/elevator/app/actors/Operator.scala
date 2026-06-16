package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto

object Operator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Operator")

  sealed trait Command

  /** Run one move. Carries plain data only (name + state + order/command) — never the
    * Elevator/Engine, so the message serializes cleanly across nodes. */
  final case class Move(elevatorName: String,
                        state: ElevatorState,
                        orderWithCommand: OrderElevatorCommand) extends Command

  /** The Operator depends on no other actor. It is handed two ports:
    *   - `publish`: emit the new state to Kafka
    *   - `report` : hand the result back to whoever asked
    * Both hide the collaborator behind a narrow function (a facade). */
  def apply(publish: ElevatorStateDto => Unit,
            report: (String, ElevatorState, OrderElevatorCommand) => Unit): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case Move(elevatorName, state, orderWithCommand) =>
          val moved = Elevator.fast(elevatorName)(state).move(orderWithCommand.command)
          val newState = ElevatorState(moved.direction(), moved.motion(), moved.floor())

          // Make every floor change observable: emit current state to Kafka.
          publish(ElevatorStateDto(
            tag = orderWithCommand.order.tag,
            elevatorName = elevatorName,
            direction = newState.direction.toString,
            motion = newState.motion.toString,
            floor = newState.floor.num
          ))

          report(elevatorName, newState, orderWithCommand)

          ctx.log.info(s" [$elevatorName]:${newState.floor.num}  >>>   ${orderWithCommand.order.floor.num}")
          Behaviors.same
    }
}
