package pl.feelcodes.elevator.common.logic

import pl.feelcodes.elevator.common.core.domain.{Call, ElevatorName, Floor, Order, OrderId}
import pl.feelcodes.elevator.common.events.ManagerEvents.*
import pl.feelcodes.elevator.common.strategy.GroupCallsStrategy

/** Owns the call↔order relation: one living order per floor that calls attach to until it is done. */
object ManagerLogic:
  final case class State(orders: Map[OrderId, Order])

  object State:
    val empty: State = State(Map.empty)

  def combine(elevatorName: ElevatorName, calls: List[Call]): Set[Order] =
    GroupCallsStrategy.default.group(elevatorName, calls)

  def plan(state: State, orders: Set[Order]): List[OrderCreated | OrderExtended] =
    orders.toList.flatMap { o =>
      state.orders.get(o.id) match
        case None => Some(OrderCreated(o.id, o.floor.num, o.callIds, o.passengers, o.anonymousCallIds))
        case Some(existing) =>
          val fresh = o.callIds -- existing.callIds
          if fresh.isEmpty then None
          else Some(OrderExtended(o.id, fresh, o.passengers -- existing.passengers, o.anonymousCallIds -- existing.anonymousCallIds))
    }

  def evolve(state: State, event: Event): State =
    event match
      case OrderCreated(id, floor, callIds, passengers, anonymousCallIds) =>
        state.copy(orders = state.orders + (id -> Order(id, Floor(floor), callIds, passengers, anonymousCallIds)))
      case OrderExtended(id, callIds, passengers, anonymousCallIds) =>
        state.copy(orders = state.orders.updatedWith(id)(_.map(o =>
          o.copy(callIds = o.callIds ++ callIds, passengers = o.passengers ++ passengers, anonymousCallIds = o.anonymousCallIds ++ anonymousCallIds))))
      case OrderDone(id) => state.copy(orders = state.orders - id)
