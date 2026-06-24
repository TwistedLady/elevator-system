package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.core.{Elevator, ElevatorState}
import pl.feelcodes.elevator.common.protocol.OperatorProtocol
import pl.feelcodes.elevator.common.strategy.OperatorStrategy

object Operator:
  export OperatorProtocol.*

  type PublishMove = (String, ElevatorState) => Unit
  type BuildElevator = (String, ElevatorState) => Elevator

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Operator")

  def apply(publishMove: PublishMove,
            buildElevator: BuildElevator): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      msg match
        case Move(elevatorName, state, command) =>
          val newState = OperatorStrategy.afterMove(buildElevator, elevatorName, state, command)
          publishMove(elevatorName, newState)
          if newState.floor.num != state.floor.num then
            context.log.info(s" [$elevatorName] ${state.floor.num} >>> ${newState.floor.num}")
          Behaviors.same
    }
