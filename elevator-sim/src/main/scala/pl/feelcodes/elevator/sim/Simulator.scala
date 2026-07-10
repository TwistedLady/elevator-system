package pl.feelcodes.elevator.sim

import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Fires a fixed burst of 100 random calls per run (parameterless scenario). CallSender is the
  * api-supplied sink (SAM, so Java can pass a lambda), keeping the engine off Kafka/HTTP. */

final case class SimSpec(id: String, elevator: String, floor: Int)

final case class SimRun(runId: String, ids: java.util.List[String])

trait CallSender:
  def fire(spec: SimSpec): Unit

object Simulator:
  val Riders: Int = 100

final class Simulator(sender: CallSender, elevators: Seq[String], maxFloor: Int, random: Random):

  require(elevators.nonEmpty, "need at least one elevator")
  require(maxFloor >= 1, "maxFloor must be >= 1")

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
