package pl.feelcodes.elevator.app.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import pl.feelcodes.elevator.common.core.{ElevatorOrder, Floor}
import pl.feelcodes.elevator.common.protocol.CoordinatorProtocol.AddOriginalStream
import pl.feelcodes.elevator.common.events.CoordinatorEvents
import pl.feelcodes.elevator.common.strategy.CoordinatorStrategy

object Coordinator:

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Coordinator")

  sealed trait Command
  final case class Process(stream: AddOriginalStream, replyTo: ActorRef[Ack]) extends Command

  sealed trait Ack
  object Ack:
    case object Ok extends Ack

  final case class State()
  object State:
    val empty: State = State()

  def apply(elevatorName: String,
            controllerProvider: String => EntityRef[Controller.Command]): Behavior[Command] =
    EventSourcedBehavior[Command, CoordinatorEvents.Event, State](
      persistenceId = PersistenceId.of(TypeKey.name, elevatorName),
      emptyState = State.empty,
      commandHandler = (_, msg) =>
        msg match
          case Process(AddOriginalStream(orders), replyTo) =>
            val events = orders.map(o => CoordinatorEvents.OrderAccepted(o.tag, o.elevatorName, o.floor))
            val merged = CoordinatorStrategy.mergeByFloor(orders.map(o => ElevatorOrder(o.tag, Floor(o.floor))))
            Effect.persist(events).thenRun { _ =>
              controllerProvider(elevatorName) ! Controller.AddUniqueOrderSet(merged)
              replyTo ! Ack.Ok
            }
      ,
      eventHandler = (state, _) => state
    )
