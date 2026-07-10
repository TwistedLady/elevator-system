package pl.feelcodes.elevator.common.logic

import pl.feelcodes.elevator.common.core.domain.Call
import pl.feelcodes.elevator.common.events.PassengerEvents.*

/** One passenger can be inside one lift at a time: while busy, calls to other lifts are frozen and
  * released one at a time (FIFO) as each travel finishes. */
object PassengerLogic:
  final case class HeldCall(elevatorName: String, call: Call)

  final case class State(busy: Boolean, held: List[HeldCall])

  object State:
    val empty: State = State(busy = false, held = Nil)

  def evolve(state: State, event: Event): State =
    event match
      case CallForwarded(_, _)     => state.copy(busy = true)
      case CallHeld(elevator, call) => state.copy(held = state.held :+ HeldCall(elevator, call))
      case Freed(_) =>
        state.held match
          case _ :: tail => state.copy(held = tail)
          case Nil       => state.copy(busy = false)
