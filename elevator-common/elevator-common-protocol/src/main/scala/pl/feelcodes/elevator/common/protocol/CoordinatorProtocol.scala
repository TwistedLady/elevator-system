package pl.feelcodes.elevator.common.protocol

/** The Pekko-free heart of the Coordinator: its events, persisted [[State]], and the pure
  * decide/evolve pair.
  *
  * Note the asymmetry with the other two aggregates: the Coordinator's *command* `Process` carries
  * a Pekko `ActorRef[Ack]` (the ask reply-to) and a DTO, so the command itself cannot live in this
  * Pekko-free module — it stays in the actor. Instead [[decide]] takes a plain [[Decision]] that the
  * actor maps its command to. Everything durable (events, state) and all logic is here. */
object CoordinatorProtocol:

  /** Pure stand-in for the actor's command — what the Coordinator is being asked to do, stripped of
    * the Pekko reply-to and the DTO wrapper. */
  sealed trait Decision
  /** A first-time order for `tag`, targeting `floor`. */
  final case class Accept(tag: String, elevatorName: String, floor: Int) extends Decision
  /** The car reached `floor`; confirm every order still waiting there. */
  final case class Reach(floor: Int) extends Decision

  sealed trait Event
  final case class Accepted(tag: String, elevatorName: String, floor: Int) extends Event
  /** Durable confirmation that the order finished. */
  final case class Completed(tag: String) extends Event

  /** Outstanding (accepted-not-completed) tags grouped by target floor. */
  final case class State(byFloor: Map[Int, Set[String]] = Map.empty)

  object State:
    val empty: State = State()

  /** Pure decision: which events this command produces. Forwarding to the Controller and acking the
    * sender are side effects the actor runs after persisting — not here. */
  def decide(state: State, decision: Decision): List[Event] =
    decision match
      case Accept(tag, elevatorName, floor) =>
        // Idempotent accept: a tag already outstanding at this floor produces no new event.
        if state.byFloor.getOrElse(floor, Set.empty).contains(tag) then Nil
        else List(Accepted(tag, elevatorName, floor))

      case Reach(floor) =>
        // Confirm every order still waiting for this floor. A revisited floor has nothing left.
        state.byFloor.getOrElse(floor, Set.empty).toList.map(Completed.apply)

  /** Pure state machine: fold one event into the state. */
  def evolve(state: State, event: Event): State =
    event match
      case Accepted(tag, _, floor) =>
        state.copy(byFloor = state.byFloor.updated(floor, state.byFloor.getOrElse(floor, Set.empty) + tag))

      case Completed(tag) =>
        state.copy(byFloor = removeTag(state.byFloor, tag))

  /** Drop `tag` from whichever floor bucket holds it, removing now-empty buckets. */
  private def removeTag(byFloor: Map[Int, Set[String]], tag: String): Map[Int, Set[String]] =
    byFloor.view.mapValues(_ - tag).filter(_._2.nonEmpty).toMap
