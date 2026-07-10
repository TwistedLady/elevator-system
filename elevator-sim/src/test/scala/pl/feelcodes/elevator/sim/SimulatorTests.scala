package pl.feelcodes.elevator.sim

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Simulator: fires exactly Riders well-formed calls, returns ids matching what it fired, and — the
  * point of injecting the seed — replays identically for the same seed while differing across seeds. */
class SimulatorTests extends AnyFunSuite, Matchers, ScalaCheckPropertyChecks:

  private def collecting(): (CallSender, ListBuffer[SimSpec]) =
    val fired = ListBuffer.empty[SimSpec]
    val sender = new CallSender:
      def fire(spec: SimSpec): Unit = fired += spec
    (sender, fired)

  private val fleet = Seq("e1", "e2", "e3")

  private def runWith(seed: Int, maxFloor: Int = 15): (SimRun, Seq[SimSpec]) =
    val (sender, fired) = collecting()
    val run = Simulator(sender, fleet, maxFloor, Random(seed)).run()
    (run, fired.toSeq)

  test("run fires exactly Riders calls through the sender"):
    val (run, fired) = runWith(1)
    fired should have size Simulator.Riders
    run.ids.asScala should have size Simulator.Riders

  test("returned ids match the fired specs, in order"):
    val (run, fired) = runWith(3)
    run.ids.asScala.toSeq shouldBe fired.map(_.id).toSeq

  test("same seed reproduces the same run — ids, elevators, floors (deterministic replay)"):
    val (runA, firedA) = runWith(42)
    val (runB, firedB) = runWith(42)
    runB.runId shouldBe runA.runId
    firedB shouldBe firedA

  test("a different seed produces a different sequence of calls"):
    runWith(1)._2 should not be runWith(2)._2

  test("maxFloor of 1 always fires floor 1"):
    val (_, fired) = runWith(7, maxFloor = 1)
    fired.map(_.floor).distinct shouldBe Seq(1)

  test("rejects an empty fleet"):
    val (sender, _) = collecting()
    an[IllegalArgumentException] should be thrownBy Simulator(sender, Seq.empty, 15, Random(1))

  test("rejects a maxFloor below 1"):
    val (sender, _) = collecting()
    an[IllegalArgumentException] should be thrownBy Simulator(sender, fleet, 0, Random(1))

  test("rejects a noShowRate outside [0, 1]"):
    val (sender, _) = collecting()
    an[IllegalArgumentException] should be thrownBy Simulator(sender, fleet, 15, Random(1), noShowRate = 1.5)

  test("property: for any seed, every call is in-fleet and within [1, maxFloor]"):
    forAll(Gen.choose(Int.MinValue, Int.MaxValue), Gen.choose(1, 40)) { (seed, maxFloor) =>
      val (_, fired) = runWith(seed, maxFloor)
      fired should have size Simulator.Riders
      fired.foreach { spec =>
        spec.id should startWith("sim-")
        fleet should contain(spec.elevator)
        spec.floor should (be >= 1 and be <= maxFloor)
      }
    }

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
