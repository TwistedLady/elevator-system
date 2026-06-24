package pl.feelcodes.elevator.common.protocol

// Reviewed — data-only command for the Coordinator: AddOriginalStream (original orders from Kafka).
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto

object CoordinatorProtocol:
  sealed trait Command
  final case class AddOriginalStream(orders: List[ElevatorOrderDto]) extends Command
