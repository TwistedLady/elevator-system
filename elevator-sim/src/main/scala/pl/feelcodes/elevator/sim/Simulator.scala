package pl.feelcodes.elevator.sim

import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Models riders as two phases: a hall call (button press) and, unless the rider is a no-show, a
  * boarding once the car opens at their floor. CallSender/BoardSender are caller-supplied sinks
  * (SAMs, so Java can pass lambdas), keeping the engine off Kafka/HTTP. */

final case class SimSpec(id: String, elevator: String, floor: Int)

final case class BoardSpec(passengerId: String, elevator: String, floor: Int)

final case class SimRun(runId: String, ids: java.util.List[String])

/** One rider: presses the hall button (`spec`) and, if `boards`, steps in `thinkMillis` after the
  * door opens; a no-show (`boards == false`) never steps in, so the door times out on them. */
final case class Rider(spec: SimSpec, boards: Boolean, thinkMillis: Int)

trait CallSender:
  def fire(spec: SimSpec): Unit

trait BoardSender:
  def fire(spec: BoardSpec): Unit

object Simulator:
  val Riders: Int = 100

  /** Fraction of riders who call but never board — the case the boarding timeout exists for. */
  val DefaultNoShowRate: Double = 0.15

  val MinThinkMillis: Int = 50
  val ThinkSpreadMillis: Int = 750

final class Simulator(sender: CallSender,
                      elevators: Seq[String],
                      maxFloor: Int,
                      random: Random,
                      noShowRate: Double = Simulator.DefaultNoShowRate):

  require(elevators.nonEmpty, "need at least one elevator")
  require(maxFloor >= 1, "maxFloor must be >= 1")
  require(noShowRate >= 0.0 && noShowRate <= 1.0, "noShowRate must be in [0, 1]")

  def this(sender: CallSender, elevators: java.util.List[String], maxFloor: Int) =
    this(sender, elevators.asScala.toSeq, maxFloor, Random(), Simulator.DefaultNoShowRate)

  val runId: String = f"${random.nextInt(0x1000000)}%06x"

  /** The fixed cohort for this run, drawn once and deterministic under a seeded `random`. */
  val riders: Vector[Rider] =
    (0 until Simulator.Riders).map { i =>
      val elevator = elevators(random.nextInt(elevators.size))
      val floor = 1 + random.nextInt(maxFloor)
      val boards = random.nextDouble() >= noShowRate
      val think = Simulator.MinThinkMillis + random.nextInt(Simulator.ThinkSpreadMillis)
      Rider(SimSpec(s"sim-$runId-$i", elevator, floor), boards, think)
    }.toVector

  /** Phase one: fire every rider's hall call through the sink. */
  def run(): SimRun =
    riders.foreach(r => sender.fire(r.spec))
    SimRun(runId, riders.map(_.spec.id).asJava)

  /** Phase two (pure): the riders who will step into `elevator` when it opens at `floor` — every
    * boarder waiting there, no-shows excluded. The caller decides when to fire each. */
  def boardingsFor(elevator: String, floor: Int): Vector[BoardSpec] =
    riders.collect {
      case Rider(SimSpec(id, e, f), true, _) if e == elevator && f == floor =>
        BoardSpec(id, elevator, floor)
    }

  /** The think-time each boarder waits after the door opens before stepping in, by passenger id. */
  def thinkMillisById: Map[String, Int] =
    riders.map(r => r.spec.id -> r.thinkMillis).toMap
