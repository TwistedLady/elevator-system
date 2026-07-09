package pl.feelcodes.elevator.common.core.domain

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

/** Elevator motor; `cost` is the real travel time one move takes (external physical action). */
trait Engine(val cost: FiniteDuration):

  protected def burn(): Unit = Thread.sleep(cost.toMillis)

  def move(floor: Floor)(command: Command): Floor =
    burn()
    command match
      case Command.Go(Direction.Up) => floor ++
      case Command.Go(Direction.Down) => floor --
      case Command.Stop() => floor
