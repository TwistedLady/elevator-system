package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.dto.ElevatorOrderDto

object Coordinator {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  sealed trait Command
  final case class Process(dto: ElevatorOrderDto, replyTo: ActorRef[Ack]) extends Command

  sealed trait Ack
  object Ack {
    case object Ok extends Ack
  }

  sealed trait Event
  final case class Accepted(tag: String, elevatorName: String, floor: Int) extends Event

  final case class State(seenTags: Set[String],
                         tagOrder: Vector[String],
                         lastAccepted: Option[ElevatorOrderDto])

  object State {
    val empty: State = State(seenTags = Set.empty, tagOrder = Vector.empty, lastAccepted = None)
  }

  def apply(elevatorName: String,
            controllerProvider: String => EntityRef[Controller.Command]): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Coordinator-$elevatorName"),
      emptyState = State.empty,
      commandHandler = commandHandler(elevatorName, controllerProvider),
      eventHandler = eventHandler
    )
  }

  private def commandHandler(elevatorName: String,
                             controllerProvider: String => EntityRef[Controller.Command])
                            (state: State, cmd: Command): Effect[Event, State] = {
    cmd match
      case Process(dto, replyTo) =>
        if state.seenTags.contains(dto.tag) then
          replyTo ! Ack.Ok
          Effect.none
        else
          Effect
            .persist(Accepted(dto.tag, dto.elevatorName, dto.floor))
            .thenRun { _ =>
              val controller = controllerProvider(elevatorName)
              controller ! Controller.AddRequest(ElevatorOrder(dto.tag, Floor(dto.floor)))
              replyTo ! Ack.Ok
            }
  }

  private val eventHandler: (State, Event) => State = { (state, event) =>
    event match
      case Accepted(tag, elevatorName, floor) =>
        val nextTagOrder = (state.tagOrder :+ tag)
        val (finalSet, finalOrder) = shrink(state.seenTags + tag, nextTagOrder, maxSize = 10_000)

        state.copy(
          seenTags = finalSet,
          tagOrder = finalOrder,
          lastAccepted = Some(ElevatorOrderDto(tag, elevatorName, floor))
        )
  }

  private def shrink(tags: Set[String], order: Vector[String], maxSize: Int): (Set[String], Vector[String]) = {
    if order.size <= maxSize then (tags, order)
    else {
      val toDrop = order.size - maxSize
      val dropped = order.take(toDrop)
      val nextOrder = order.drop(toDrop)
      val nextTags = tags -- dropped
      (nextTags, nextOrder)
    }
  }
}
