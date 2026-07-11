package pl.feelcodes.elevator.optimizer

/** Tiny, self-contained mirror of the real elevator domain.
 *  Only what the optimization lab needs: floors, a SCAN mover, per-move cost. */

sealed trait Direction { def swap: Direction }
case object Up   extends Direction { def swap = Down }
case object Down extends Direction { def swap = Up }

sealed trait Command
case object Stop        extends Command
case class  Go(d: Direction) extends Command

/** SCAN / elevator-algorithm: same rule as the app's NextFloorStrategy.default.
 *  Stop if standing on a target, else keep going while a target is ahead,
 *  else reverse if any target remains, else stop. */
object Scan {
  def next(current: Int, dir: Direction, targets: Set[Int]): Command = {
    if (targets.contains(current)) Stop
    else if (targetAhead(current, dir, targets)) Go(dir)
    else if (targets.nonEmpty) Go(dir.swap)
    else Stop
  }

  private def targetAhead(current: Int, dir: Direction, targets: Set[Int]): Boolean =
    dir match {
      case Up   => targets.exists(_ > current)
      case Down => targets.exists(_ < current)
    }
}

/** One passenger call: arrives at `requestTick`, waits at `floor` for a pickup. */
case class Call(id: Int, requestTick: Int, floor: Int)

/** A single elevator's mutable state during one simulation run. */
class Car(val name: String, startFloor: Int) {
  var floor: Int          = startFloor
  var dir: Direction      = Up
  var targets: Set[Int]   = Set.empty
  var floorsTravelled: Int = 0

  def load: Int = targets.size
  def idle: Boolean = targets.isEmpty

  /** Advance one move; return true if we stopped on a target floor. */
  def step(): Boolean =
    Scan.next(floor, dir, targets) match {
      case Stop      => targets.nonEmpty  // stopped because we reached a target
      case Go(Up)    => floor += 1; dir = Up;   floorsTravelled += 1; false
      case Go(Down)  => floor -= 1; dir = Down; floorsTravelled += 1; false
    }
}
