package pl.feelcodes.elevator.common.logic

import pl.feelcodes.elevator.common.core.domain.{Call, Floor, Order, OrderId}
import pl.feelcodes.elevator.common.events.ManagerEvents.*
import pl.feelcodes.elevator.common.strategy.GroupCallsStrategy

/** Owns the call↔order relation: groups calls into orders and remembers each order's calls. */
object ManagerLogic:
  final case class State(orders: Map[OrderId, Order])

  object State:
    val empty: State = State(Map.empty)

  def combine(calls: List[Call]): Set[Order] =
    GroupCallsStrategy.default.group(calls)

  def created(state: State, orders: Set[Order]): List[OrderCreated] =
    orders.toList.filterNot(o => state.orders.contains(o.id)).map(o => OrderCreated(o.id, o.floor.num, o.callIds))

  def evolve(state: State, event: Event): State =
    event match
      case OrderCreated(id, floor, callIds) => state.copy(orders = state.orders + (id -> Order(id, Floor(floor), callIds)))
      case OrderDone(id)                    => state.copy(orders = state.orders - id)
