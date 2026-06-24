package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.*
import pl.feelcodes.elevator.common.core.Command.{Go, Stop}

object NextFloorStrategy:
  def chooseNextAndBuildCommand(current: Floor, direction: Direction, targets: Set[Floor]): Command =
    if targets.contains(current) then Stop()
    else if hasTargetAhead(current, direction, targets) then Go(direction)
    else if targets.nonEmpty then Go(direction.swap)
    else Stop()

  private def hasTargetAhead(current: Floor, direction: Direction, targets: Set[Floor]): Boolean =
    direction match
      case Direction.Up => targets.exists(_.num > current.num)
      case Direction.Down => targets.exists(_.num < current.num)
