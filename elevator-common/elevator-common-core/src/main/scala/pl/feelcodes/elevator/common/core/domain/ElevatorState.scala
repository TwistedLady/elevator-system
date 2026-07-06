package pl.feelcodes.elevator.common.core.domain

type ElevatorName = String

/** Travel direction; `swap` reverses it. */
enum Direction:
  case Up, Down

  def swap: Direction = this match
    case Up => Down
    case Down => Up

enum Motion:
  case Moving, Stopped

/** A move command: go one way, or stop. */
enum Command:
  case Go(direction: Direction)
  case Stop()

/** Immutable elevator snapshot: floor + motion. */
final case class ElevatorState(direction: Direction,
                               motion: Motion,
                               floor: Floor):
  require(direction != null, "Illegal state: direction must not be null")
  require(motion != null, "Illegal state: motion must not be null")
  require(floor != null, "Illegal floor: floor must not be null")
