package pl.feelcodes.elevator.common.protocol

// Reviewed — data-only commands for the Coordinator: AddOriginalStream (original Kafka orders), MarkOrderDone.
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto

object CoordinatorProtocol:
  sealed trait Command
  final case class AddOriginalStream(orders: List[ElevatorOrderDto]) extends Command
  final case class MarkOrderDone(tag: String) extends Command
