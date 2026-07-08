package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.domain.*

trait SuspendStrategy:
  def mayMove(state: ElevatorState): Boolean

object SuspendStrategy:
  val default: SuspendStrategy = _ => true
