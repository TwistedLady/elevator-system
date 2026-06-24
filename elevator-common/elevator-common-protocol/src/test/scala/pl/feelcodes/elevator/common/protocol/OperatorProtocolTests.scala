package pl.feelcodes.elevator.common.protocol

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.*
import pl.feelcodes.elevator.common.core.Command.*

/**
 * The Operator's pure transition: build the car with the injected engine strategy and advance it
 * one step. Uses the fast engine so the burn() loop is negligible.
 */
final class OperatorProtocolTests extends AnyFunSuite:
  import OperatorProtocol.*

  private val fast: BuildElevator = (name, state) => Elevator.fast(name)(state)

  test("afterMove | Go(Up) from floor 0 -> floor 1, Up, Moving"):
    val owc = OrderElevatorCommand(ElevatorOrder("o-1", Floor(1)), Go(Up))
    val next = afterMove(fast, "lift-a", ElevatorState(Up, Stopped, Floor(0)), owc)
    assert(next == ElevatorState(Up, Moving, Floor(1)))

  test("afterStop | a moving car stops, floor and direction unchanged"):
    val next = afterStop(fast, "lift-a", ElevatorState(Up, Moving, Floor(3)))
    assert(next == ElevatorState(Up, Stopped, Floor(3)))
