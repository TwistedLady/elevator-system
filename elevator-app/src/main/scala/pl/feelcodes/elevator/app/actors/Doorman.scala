package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.core.domain.{DoorState, Floor}
import pl.feelcodes.elevator.common.core.engine.DoorEngine
import pl.feelcodes.elevator.common.protocol.DoormanProtocol

object Doorman:
  export DoormanProtocol.*

  type PublishDoor = (String, Floor, DoorState) => Unit

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Doorman")

  def apply(publishDoor: PublishDoor, engine: DoorEngine): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      msg match
        case Serve(elevatorName, floor) =>
          publishDoor(elevatorName, floor, DoorState.Open)
          engine.burn()
          publishDoor(elevatorName, floor, DoorState.Closed)
          context.log.info(s" [$elevatorName] door @ ${floor.num} open>close")
          Behaviors.same
    }
