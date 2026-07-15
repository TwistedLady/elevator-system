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

  test("rejects a noShowRate outside [0, 1]"):
    val (sender, _) = collecting()
    an[IllegalArgumentException] should be thrownBy Simulator(sender, fleet, 15, Random(1), noShowRate = 1.5)

  test("boardingsFor returns only boarders at the given elevator and floor"):
    val (sender, _) = collecting()
    val sim = Simulator(sender, fleet, maxFloor = 15, Random(7))
    val boarders = sim.boardingsFor("e1", 5)
    boarders.foreach: b =>
      b.elevator shouldBe "e1"
      b.floor shouldBe 5
    val expected = sim.riders.count(r => r.boards && r.spec.elevator == "e1" && r.spec.floor == 5)
    boarders should have size expected
    boarders.map(_.passengerId).toSet shouldBe
      sim.riders.filter(r => r.boards && r.spec.elevator == "e1" && r.spec.floor == 5).map(_.spec.id).toSet

  test("no-shows are excluded from boardings but still fired as calls"):
    val (sender, fired) = collecting()
    val sim = Simulator(sender, fleet, maxFloor = 15, Random(7), noShowRate = 1.0)
    sim.run()
    fired should have size Simulator.Riders
    fleet.foreach: e =>
      (1 to 15).foreach: f =>
        sim.boardingsFor(e, f) shouldBe empty

  test("everyone boards when noShowRate is zero"):
    val (sender, _) = collecting()
    val sim = Simulator(sender, fleet, maxFloor = 15, Random(7), noShowRate = 0.0)
    val boarded = fleet.flatMap(e => (1 to 15).flatMap(f => sim.boardingsFor(e, f)))
    boarded.map(_.passengerId).toSet shouldBe sim.riders.map(_.spec.id).toSet

  test("the rider cohort is deterministic under a fixed seed"):
    val (s1, _) = collecting()
    val (s2, _) = collecting()
    Simulator(s1, fleet, 15, Random(42)).riders shouldBe Simulator(s2, fleet, 15, Random(42)).riders
