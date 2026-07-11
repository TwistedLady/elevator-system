package pl.feelcodes.elevator.app.lab

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import pl.feelcodes.elevator.app.actors.Doorman
import pl.feelcodes.elevator.common.core.domain.{DoorState, Floor}
import pl.feelcodes.elevator.sim.{CallSender, Rider, Simulator}

import scala.concurrent.duration.*
import scala.util.Random

/** A self-contained lab for the "passenger steps in" case: it wires the real [[Doorman]] and lets
  * the [[Simulator]] play the passenger. One rider = one stop: the car opens at their floor and the
  * rider either steps in (a Boarded closes the door early) or is a no-show (the door times out).
  * Every wait is a message-driven state — nothing blocks a thread.
  *
  * Run it:  ./mvnw -q -pl elevator-app -am -Pexec exec:java \
  *            -Dexec.mainClass=pl.feelcodes.elevator.app.lab.BoardingLab [seed] */
object BoardingLab:

  final case class Outcome(floor: Int, passenger: String, boarded: Boolean)
  final case class Summary(elevator: String, outcomes: List[Outcome]):
    def boarded: Int = outcomes.count(_.boarded)
    def noShows: Int = outcomes.count(!_.boarded)

  /** The driver owns the Doorman (as a child, so its door callback can post back here) and walks the
    * riders through one open/close handshake each. */
  object LabDriver:
    sealed trait Command
    private final case class DoorOpened(floor: Int) extends Command
    private final case class DoorClosed(floor: Int) extends Command
    private final case class StepIn(floor: Int, passenger: String) extends Command

    def apply(elevator: String,
              riders: List[Rider],
              boardTimeout: FiniteDuration,
              thinkOf: Rider => FiniteDuration,
              replyTo: ActorRef[Summary]): Behavior[Command] =
      Behaviors.setup { ctx =>
        Behaviors.withTimers { timers =>
          val doorman = ctx.spawn(
            Doorman((_, floor, doorState) =>
              doorState match
                case DoorState.Open   => ctx.self ! DoorOpened(floor.num)
                case DoorState.Closed => ctx.self ! DoorClosed(floor.num),
              boardTimeout),
            "doorman")

          def serveNext(remaining: List[Rider], acc: List[Outcome]): Behavior[Command] =
            remaining match
              case Nil =>
                replyTo ! Summary(elevator, acc.reverse)
                Behaviors.stopped
              case rider :: rest =>
                doorman ! Doorman.Serve(elevator, Floor(rider.spec.floor))
                serving(rider, rest, acc, steppedIn = false)

          def serving(rider: Rider,
                      rest: List[Rider],
                      acc: List[Outcome],
                      steppedIn: Boolean): Behavior[Command] =
            val floor = rider.spec.floor
            Behaviors.receiveMessage {
              case DoorOpened(f) if f == floor =>
                if rider.boards then
                  timers.startSingleTimer(StepIn(f, rider.spec.id), thinkOf(rider))
                Behaviors.same

              case StepIn(f, passenger) if f == floor =>
                doorman ! Doorman.Boarded(elevator, Floor(f), passenger)
                serving(rider, rest, acc, steppedIn = true)

              case DoorClosed(f) if f == floor =>
                serveNext(rest, Outcome(floor, rider.spec.id, steppedIn) :: acc)

              case _ =>
                Behaviors.same
            }

          serveNext(riders, Nil)
        }
      }

  /** Riders who called this elevator, in call order. */
  def ridersOf(sim: Simulator, elevator: String): List[Rider] =
    sim.riders.filter(_.spec.elevator == elevator).toList

  def guardian(elevator: String,
               riders: List[Rider],
               boardTimeout: FiniteDuration,
               thinkOf: Rider => FiniteDuration): Behavior[Summary] =
    Behaviors.setup { ctx =>
      ctx.spawn(LabDriver(elevator, riders, boardTimeout, thinkOf, ctx.self), "driver")
      Behaviors.receiveMessage { summary =>
        println(render(summary))
        Behaviors.stopped
      }
    }

  private def render(s: Summary): String =
    val rows = s.outcomes.map { o =>
      val verdict = if o.boarded then s"stepped in (${o.passenger})" else "no-show → door timed out"
      f"  floor ${o.floor}%2d  $verdict"
    }
    (s"— boarding lab: ${s.elevator}, ${s.outcomes.size} stop(s) —" ::
      rows :::
      s"— ${s.boarded} boarded, ${s.noShows} timed out —" :: Nil).mkString("\n")

  def main(args: Array[String]): Unit =
    val seed = args.headOption.map(_.toInt).getOrElse(1)
    val elevator = "lift-a"
    val logCalls: CallSender = spec => println(f"  call: floor ${spec.floor}%2d  (${spec.id})")
    val sim = Simulator(logCalls, Seq(elevator), maxFloor = 6, Random(seed), noShowRate = 0.25)
    val riders = ridersOf(sim, elevator).take(12)
    println(s"— firing ${riders.size} hall calls (seed $seed) —")
    riders.foreach(r => logCalls.fire(r.spec))
    // A plain local ActorSystem — the lab needs no cluster/persistence/Kafka, so it does NOT load
    // the app's application.conf; only Pekko's own reference defaults (provider = local).
    val config = ConfigFactory.parseString("pekko.actor.provider = local")
      .withFallback(ConfigFactory.defaultReference())
    val system = ActorSystem(
      guardian(elevator, riders, boardTimeout = 2.seconds, thinkOf = r => r.thinkMillis.millis),
      "boarding-lab", config)
    scala.concurrent.Await.ready(system.whenTerminated, 120.seconds)
