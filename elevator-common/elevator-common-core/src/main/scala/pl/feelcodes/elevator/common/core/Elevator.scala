package pl.feelcodes.elevator.common.core

import pl.feelcodes.elevator.common.core.Command.{Go, Stop}
import pl.feelcodes.elevator.common.core.Direction.*
import pl.feelcodes.elevator.common.core.Motion.{Moving, Stopped}

import scala.language.postfixOps

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

trait Engine(val cost: BigInt):

  protected def burn(): Unit =
    var i = 0
    while i < cost do i += 1

  def move(floor: Floor)(command: Command): Floor =
    burn()
    command match
      case Go(Direction.Up) => floor ++
      case Go(Direction.Down) => floor --
      case Stop() => floor

final case class SlowEngine() extends Engine(500_000_000)

final case class FastEngine() extends Engine(2_000)

final case class Elevator(name: ElevatorName,
                          private val engine: Engine,
                          private val state: ElevatorState):
  def move(command: Command): Elevator = {
    copy(
      state = state.copy(
        floor = engine.move(state.floor)(command),
        direction = command match
          case Go(Direction.Up) => Up
          case Go(Direction.Down) => Down
          case Stop() => state.direction,
        motion = command match
          case Go(_) => Moving
          case Stop() => Stopped))
  }


  def stop(): Elevator = copy(state = state.copy(motion = Motion.Stopped))

  def floor(): Floor = state.floor

  def direction(): Direction = state.direction

  def motion(): Motion = state.motion


object Elevator:

  val defaultState = ElevatorState(
    direction = Direction.Up,
    motion = Motion.Stopped,
    floor = Floor(0))

  def slow(name: ElevatorName)(state: ElevatorState = defaultState): Elevator =
    Elevator(name, SlowEngine(), state)

  def fast(name: ElevatorName)(state: ElevatorState = defaultState): Elevator =
    Elevator(name, FastEngine(), state)
