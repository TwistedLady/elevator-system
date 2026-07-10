package pl.feelcodes.elevator.common.events

import pl.feelcodes.elevator.common.core.domain.{Order, ElevatorState}

/** Controller scheduling events: order accepted, waiting flag, elevator state updated.
  * OrderAdded is the legacy name of OrderAccepted, kept so pre-rename journals recover. */
object ControllerEvents:
  sealed trait Event
  final case class OrderAccepted(order: Order) extends Event
  final case class WaitingSet(waiting: Boolean) extends Event
  final case class ElevatorStateUpdated(state: ElevatorState) extends Event
  final case class OrderAdded(order: Order) extends Event
