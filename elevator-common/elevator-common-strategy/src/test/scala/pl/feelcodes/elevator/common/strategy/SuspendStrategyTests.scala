package pl.feelcodes.elevator.common.strategy

import org.scalatest.funsuite.AnyFunSuite
import pl.feelcodes.elevator.common.core.domain.*

final class SuspendStrategyTests extends AnyFunSuite:
  private val stopped = ElevatorState(Direction.Up, Motion.Stopped, Floor(0))

  test("default strategy always allows movement"):
    assert(SuspendStrategy.default.mayMove(stopped))
