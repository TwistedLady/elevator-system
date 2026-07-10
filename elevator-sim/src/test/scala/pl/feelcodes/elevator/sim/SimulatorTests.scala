package pl.feelcodes.elevator.sim

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Random

class SimulatorTests extends AnyFunSuite, Matchers:

  private def collecting(): (CallSender, ListBuffer[SimSpec]) =
    val fired = ListBuffer.empty[SimSpec]
    val sender = new CallSender:
      def fire(spec: SimSpec): Unit = fired += spec
    (sender, fired)

  private val fleet = Seq("e1", "e2", "e3")

  test("run fires exactly Riders calls through the sender"):
    val (sender, fired) = collecting()
    val run = Simulator(sender, fleet, maxFloor = 15, Random(1)).run()
    fired should have size Simulator.Riders
    run.ids.asScala should have size Simulator.Riders

  test("every call is a valid, well-formed spec"):
    val (sender, fired) = collecting()
    val run = Simulator(sender, fleet, maxFloor = 15, Random(2)).run()
    fired.foreach: spec =>
      spec.id should startWith(s"sim-${run.runId}-")
      fleet should contain(spec.elevator)
      spec.floor should (be >= 1 and be <= 15)

  test("returned ids match the fired specs, in order"):
    val (sender, fired) = collecting()
    val run = Simulator(sender, fleet, maxFloor = 9, Random(3)).run()
    run.ids.asScala.toSeq shouldBe fired.map(_.id).toSeq

  test("two runs get distinct run ids"):
    val (s1, _) = collecting()
    val (s2, _) = collecting()
    val a = Simulator(s1, fleet, maxFloor = 15, Random(10)).run()
    val b = Simulator(s2, fleet, maxFloor = 15, Random(20)).run()
    a.runId should not be b.runId

  test("rejects an empty fleet"):
    val (sender, _) = collecting()
    an[IllegalArgumentException] should be thrownBy Simulator(sender, Seq.empty, 15, Random(1))
