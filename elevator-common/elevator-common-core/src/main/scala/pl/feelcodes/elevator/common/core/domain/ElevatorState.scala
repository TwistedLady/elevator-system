package pl.feelcodes.elevator.common.core.domain

/** Elevator domain values: Direction (`swap` reverses), Motion, a move Command, and the
  * immutable ElevatorState snapshot (direction, motion, floor). */

type ElevatorName = String

enum Direction:
  case Up, Down

  def swap: Direction = this match
    case Up => Down
    case Down => Up

enum Motion:
  case Moving, Stopped

enum Command:
  case Go(direction: Direction)
  case Stop()

final case class ElevatorState(direction: Direction,
                               motion: Motion,
                               floor: Floor):
  require(direction != null, "Illegal state: direction must not be null")
  require(motion != null, "Illegal state: motion must not be null")
  require(floor != null, "Illegal floor: floor must not be null")
