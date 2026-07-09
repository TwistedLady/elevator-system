package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Motion.*
import pl.feelcodes.elevator.common.core.domain.Command.*

final class OperatorTests extends AnyFunSuite:

  private val fast: Operator.BuildElevator = (name, state) => Elevator.fast(name)(state)

  private def moveYields(state: ElevatorState, command: Command): ElevatorState =
    var published: ElevatorState = null
    val kit = BehaviorTestKit(Operator((_, s) => published = s, fast))
    kit.run(Operator.Move("lift-a", state, command))
    published

  test("Move | Go(Up) from floor 0 -> floor 1, Up, Moving"):
    assert(moveYields(ElevatorState(Up, Stopped, Floor(0)), Go(Up)) == ElevatorState(Up, Moving, Floor(1)))

  test("Move | Stop() halts a moving car, floor and direction unchanged"):
    assert(moveYields(ElevatorState(Up, Moving, Floor(3)), Stop()) == ElevatorState(Up, Stopped, Floor(3)))
