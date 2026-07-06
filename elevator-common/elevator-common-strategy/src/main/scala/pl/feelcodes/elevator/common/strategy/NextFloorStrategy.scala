package pl.feelcodes.elevator.common.strategy

import pl.feelcodes.elevator.common.core.domain.*
import pl.feelcodes.elevator.common.core.domain.Command.{Go, Stop}

trait NextFloorStrategy:
  def next(current: Floor, direction: Direction, targets: Set[Floor]): Command

object NextFloorStrategy:
  val default: NextFloorStrategy = (current, direction, targets) =>
    if targets.contains(current) then Stop()
    else if targetAhead(current, direction, targets) then Go(direction)
    else if targets.nonEmpty then Go(direction.swap)
    else Stop()

  private def targetAhead(current: Floor, direction: Direction, targets: Set[Floor]): Boolean =
    direction match
      case Direction.Up   => targets.exists(_.num > current.num)
      case Direction.Down => targets.exists(_.num < current.num)
