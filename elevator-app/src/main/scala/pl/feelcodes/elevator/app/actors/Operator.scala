package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import pl.feelcodes.elevator.app.actors.Controller.ConfirmProcessed
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorStateDto

object Operator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Operator")


  sealed trait Command

  case class Do(elevator: Elevator,
                orderWithCommand: OrderElevatorCommand) extends Command

  def apply(controllerProvider: (elevatorName: String) => EntityRef[Controller.Command],
            publish: ElevatorStateDto => Unit): Behavior[Command] =
    running(controllerProvider, publish)

  private def running(controllerProvider: (elevatorName: String) => EntityRef[Controller.Command],
                      publish: ElevatorStateDto => Unit): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case Do(elevator, command) =>
          val next = elevator.move(command.command)

          // Make every floor change observable: emit current state to Kafka.
          publish(ElevatorStateDto(
            tag = command.order.tag,
            elevatorName = next.name,
            direction = next.direction().toString,
            motion = next.motion().toString,
            floor = next.floor().num
          ))

          controllerProvider.apply(next.name)
            ! ConfirmProcessed(next, command)

          ctx.log.info(s" [${next.name}]:${next.floor().num}  >>>   ${command.order.floor.num}")
          running(controllerProvider, publish)
    }
}
