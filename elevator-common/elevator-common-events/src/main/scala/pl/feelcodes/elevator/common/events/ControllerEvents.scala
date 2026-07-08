package pl.feelcodes.elevator.common.events

import pl.feelcodes.elevator.common.core.domain.{Order, ElevatorState}

/** Controller scheduling events: an order added, the waiting flag, and a new elevator state. */
object ControllerEvents:
  sealed trait Event
  final case class OrderAccepted(order: Order) extends Event
  final case class WaitingSet(waiting: Boolean) extends Event
  final case class ElevatorStateUpdated(state: ElevatorState) extends Event

  /** Legacy name of OrderAccepted, kept so pre-rename journal events still recover. */
  final case class OrderAdded(order: Order) extends Event
