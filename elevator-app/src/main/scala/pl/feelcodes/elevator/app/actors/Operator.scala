package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey
import pl.feelcodes.elevator.common.protocol.OperatorProtocol

/** Pekko shell around [[OperatorProtocol]]. A dumb worker: it executes exactly the command the
  * Controller hands it (via the pure transition in the protocol) and reports the result through its
  * two report ports. It does NOT publish state and does NOT decide whether to move or stop — that's
  * the Controller's job.
  *
  * `export` re-exposes the protocol's messages and types under `Operator.*`, so call sites and the
  * serialization config keep using `Operator.Move` etc. (the runtime classes live in the protocol
  * module). */
object Operator:
  export OperatorProtocol.*

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Operator")

  def apply(reportMove: ReportMove,
            reportStop: ReportStop,
            buildElevator: BuildElevator): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      msg match
        case Move(elevatorName, state, command) =>
          val newState = OperatorProtocol.afterMove(buildElevator, elevatorName, state, command)
          reportMove(elevatorName, newState)
          if newState.floor.num != state.floor.num then
            context.log.info(s" [$elevatorName] ${state.floor.num} >>> ${newState.floor.num}")
          Behaviors.same

        case Stop(elevatorName, state) =>
          val newState = OperatorProtocol.afterStop(buildElevator, elevatorName, state)
          reportStop(elevatorName, newState)
          Behaviors.same
    }
