package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{Floor, OrderElevator}
import pl.feelcodes.elevator.common.dto.OrderElevatorAtDto

object Coordinator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  sealed trait Command
  final case class Process(dto: OrderElevatorAtDto, replyTo: ActorRef[Ack]) extends Command

  sealed trait Ack
  object Ack {
    case object Ok extends Ack
  }

  sealed trait Event
  final case class Accepted(messageId: String, elevatorId: String, targetFloor: Int) extends Event

  final case class State(seenMessageIds: Set[String],
                         seenOrder: Vector[String],
                         lastAccepted: Option[OrderElevatorAtDto])

  object State {
    val empty: State = State(seenMessageIds = Set.empty, seenOrder = Vector.empty, lastAccepted = None)
  }

  def apply(elevatorId: String,
            controllerProvider: String => EntityRef[Controller.Msg]): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Coordinator-$elevatorId"),
      emptyState = State.empty,
      commandHandler = commandHandler(elevatorId, controllerProvider),
      eventHandler = eventHandler
    )
  }

  private def commandHandler(elevatorName: String,
                             controllerProvider: String => EntityRef[Controller.Msg])
                            (state: State, cmd: Command): Effect[Event, State] = {
    cmd match
      case Process(dto, replyTo) =>
        if state.seenMessageIds.contains(dto.tag) then
          replyTo ! Ack.Ok
          Effect.none
        else
          Effect
            .persist(Accepted(dto.tag, dto.elevatorName, dto.floor))
            .thenRun { _ =>
              val controller = controllerProvider(elevatorName)
              controller ! Controller.ToProcess(OrderElevator(dto.tag, Floor(dto.floor)))
              replyTo ! Ack.Ok
            }
  }

  private val eventHandler: (State, Event) => State = { (state, event) =>
    event match
      case Accepted(messageId, eId, targetFloor) =>
        val nextOrder = (state.seenOrder :+ messageId)
        val (finalSet, finalOrder) = shrink(state.seenMessageIds + messageId, nextOrder, maxSize = 10_000)

        state.copy(
          seenMessageIds = finalSet,
          seenOrder = finalOrder,
          lastAccepted = Some(OrderElevatorAtDto(messageId, eId, targetFloor))
        )
  }

  private def shrink(ids: Set[String], order: Vector[String], maxSize: Int): (Set[String], Vector[String]) = {
    if order.size <= maxSize then (ids, order)
    else {
      val toDrop = order.size - maxSize
      val dropped = order.take(toDrop)
      val nextOrder = order.drop(toDrop)
      val nextIds = ids -- dropped
      (nextIds, nextOrder)
    }
  }
}