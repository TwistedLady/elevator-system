package pl.feelcodes.elevator.sim

import scala.jdk.CollectionConverters.*
import scala.util.Random

/** One call the simulator wants placed: a floor request on an elevator, with a stable id. */
final case class SimSpec(id: String, elevator: String, floor: Int)

/** The outcome of a run: its id and the ids of the calls fired (in order). */
final case class SimRun(runId: String, ids: java.util.List[String])

/** How the simulator places a call. The api supplies an impl backed by its call path; this keeps
  * the engine free of Kafka/HTTP. Single abstract method, so Java can pass a lambda. */
trait CallSender:
  def fire(spec: SimSpec): Unit

object Simulator:
  /** Calls fired per run — one per would-be rider. Fixed on purpose: a run takes no parameters. */
  val Riders: Int = 100

/** Fires a fixed burst of random calls. `run()` takes no arguments — the scenario is fixed. */
final class Simulator(sender: CallSender, elevators: Seq[String], maxFloor: Int, random: Random):

  require(elevators.nonEmpty, "need at least one elevator")
  require(maxFloor >= 1, "maxFloor must be >= 1")

  /** Java entry point: a fresh RNG and the fleet as a java.util.List. */
  def this(sender: CallSender, elevators: java.util.List[String], maxFloor: Int) =
    this(sender, elevators.asScala.toSeq, maxFloor, Random())

  def run(): SimRun =
    val runId = f"${random.nextInt(0x1000000)}%06x"
    val specs = (0 until Simulator.Riders).map: i =>
      val elevator = elevators(random.nextInt(elevators.size))
      val floor = 1 + random.nextInt(maxFloor)
      SimSpec(s"sim-$runId-$i", elevator, floor)
    specs.foreach(sender.fire)
    SimRun(runId, specs.map(_.id).asJava)
