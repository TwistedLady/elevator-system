package pl.feelcodes.elevator.common.logic

import pl.feelcodes.elevator.common.core.domain.{CallId, FloorNum}
import pl.feelcodes.elevator.common.events.CoordinatorEvents.*

/** Tracks in-flight calls (id → floor) so a done call can be reported with its floor. */
object CoordinatorLogic:
  final case class State(calls: Map[CallId, FloorNum])

  object State:
    val empty: State = State(Map.empty)

  def evolve(state: State, event: Event): State =
    event match
      case CallReceived(callId, floor) => state.copy(calls = state.calls + (callId -> floor))
      case CallAssigned(_, _)          => state
      case CallDone(callId)            => state.copy(calls = state.calls - callId)
