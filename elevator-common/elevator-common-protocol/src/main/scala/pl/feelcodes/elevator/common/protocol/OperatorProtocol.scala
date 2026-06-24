package pl.feelcodes.elevator.common.protocol

import pl.feelcodes.elevator.common.core.*

/** The Pekko-free heart of the Operator: its message protocol, its collaboration-port types, and
  * the pure state transition. The actor (in elevator-app) is only a thin Pekko shell around this —
  * it receives a [[Command]], calls one of the transitions below, and fires the report ports.
  *
  * Messages carry plain data (name + state + command), never the Elevator/Engine, so they
  * serialize cleanly across cluster nodes. */
object OperatorProtocol:

  sealed trait Command

  /** Run one move toward an order (the order carries the Go command). */
  final case class Move(elevatorName: String,
                        state: ElevatorState,
                        orderWithCommand: OrderElevatorCommand) extends Command

  /** Stop the car. The Operator has the ability to stop but never decides to — the Controller
    * sends this when it has no more requests. */
  final case class Stop(elevatorName: String, state: ElevatorState) extends Command

  /** Two narrow report ports back to the Controller (the Operator's only collaborator):
    *   - `reportMove`: the new state after a move (carries the order it was serving)
    *   - `reportStop`: the new (stopped) state */
  type ReportMove = (String, ElevatorState, OrderElevatorCommand) => Unit
  type ReportStop = (String, ElevatorState) => Unit

  /** The strategy: the single varying step — how to build the elevator (i.e. which engine) at a
    * given state. Everything else is identical, so it's a plain factory function, not a subclass.
    * See `Elevator.fast` / `Elevator.slow`. */
  type BuildElevator = (String, ElevatorState) => Elevator

  /** Pure transition for a Move: build the car with the chosen engine and advance it one step. */
  def afterMove(buildElevator: BuildElevator,
                elevatorName: String,
                state: ElevatorState,
                orderWithCommand: OrderElevatorCommand): ElevatorState =
    stateOf(buildElevator(elevatorName, state).move(orderWithCommand.command))

  /** Pure transition for a Stop. */
  def afterStop(buildElevator: BuildElevator,
                elevatorName: String,
                state: ElevatorState): ElevatorState =
    stateOf(buildElevator(elevatorName, state).stop())

  private def stateOf(e: Elevator): ElevatorState =
    ElevatorState(e.direction(), e.motion(), e.floor())
