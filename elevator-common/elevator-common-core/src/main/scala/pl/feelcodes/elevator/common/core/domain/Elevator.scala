package pl.feelcodes.elevator.common.core.domain

import pl.feelcodes.elevator.common.core.domain.Command.{Go, Stop}
import pl.feelcodes.elevator.common.core.domain.Direction.*
import pl.feelcodes.elevator.common.core.domain.Motion.{Moving, Stopped}
import pl.feelcodes.elevator.common.core.engine.{FastEngine, SlowEngine}

/** Elevator = name + engine + state; `move` advances it one command. */
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
