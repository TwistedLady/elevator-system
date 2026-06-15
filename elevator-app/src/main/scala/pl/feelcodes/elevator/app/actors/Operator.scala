package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import pl.feelcodes.elevator.app.actors.Controller.ConfirmProcessed
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.dto.ElevatorAtDto

object Operator {
  val TypeKey: EntityTypeKey[Msg] = EntityTypeKey[Msg]("Operator")


  sealed trait Msg

  case class Do(elevator: Elevator,
                orderWithCommand: OrderElevatorCommand) extends Msg

  def apply(controllerRefProvider: (elevatorId: String) => EntityRef[Controller.Msg],
            publish: ElevatorAtDto => Unit): Behavior[Msg] =
    running(controllerRefProvider, publish)

  private def running(controllerRefProvider: (elevatorId: String) => EntityRef[Controller.Msg],
                      publish: ElevatorAtDto => Unit): Behavior[Msg] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case Do(elevator, command) =>
          val next = elevator.move(command.command)

          // Make every floor change observable: emit current state to Kafka.
          publish(ElevatorAtDto(
            tag = command.order.tag,
            elevatorName = next.name,
            direction = next.direction().toString,
            motion = next.motion().toString,
            floor = next.floor().num
          ))

          controllerRefProvider.apply(next.name)
            ! ConfirmProcessed(next, command)

          ctx.log.info(s" [${next.name}]:${next.floor().num}  >>>   ${command.order.floor.num}")
          running(controllerRefProvider, publish)
    }
}
