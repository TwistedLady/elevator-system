package pl.feelcodes.elevator.app.actors

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.*
import pl.feelcodes.elevator.common.core.Command.*
import pl.feelcodes.elevator.common.strategy.OperatorStrategy

final class OperatorTests extends AnyFunSuite:

  private val fast: Operator.BuildElevator = (name, state) => Elevator.fast(name)(state)

  test("afterMove | Go(Up) from floor 0 -> floor 1, Up, Moving"):
    val next = OperatorStrategy.afterMove(fast, "lift-a", ElevatorState(Up, Stopped, Floor(0)), Go(Up))
    assert(next == ElevatorState(Up, Moving, Floor(1)))

  test("afterMove | Stop() halts a moving car, floor and direction unchanged"):
    val next = OperatorStrategy.afterMove(fast, "lift-a", ElevatorState(Up, Moving, Floor(3)), Stop())
    assert(next == ElevatorState(Up, Stopped, Floor(3)))
