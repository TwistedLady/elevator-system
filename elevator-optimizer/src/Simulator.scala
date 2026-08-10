package pl.feelcodes.elevator.optimizer

import scala.collection.mutable
import scala.util.Random

/** The fixed scenario: same calls every run, so f(weights) is reproducible.
 *  This is the "limited resources" world we optimise inside. */
case class Scenario(elevators: Int, floors: Int, requests: Int, seed: Long) {
  /** Deterministic call list — depends ONLY on the seed, never on the optimiser. */
  val calls: Vector[Call] = {
    val rng = new Random(seed)
    val horizon = math.max(requests, 1) // spread arrivals across this many ticks
    (0 until requests).map { i =>
      Call(id = i, requestTick = rng.nextInt(horizon), floor = 1 + rng.nextInt(floors))
    }.sortBy(_.requestTick).toVector
  }
}

/** What one simulation run produced — the raw material for the target function. */
case class RunResult(waits: Vector[Int], served: Int, total: Int, floorsTravelled: Int) {
  def unfinished: Int = total - served
  def avgWait: Double = if (waits.isEmpty) 0.0 else waits.sum.toDouble / waits.size
  def p95Wait: Int = {
    if (waits.isEmpty) 0
    else {
      val sorted = waits.sorted
      sorted(math.min(sorted.size - 1, (sorted.size * 95) / 100))
    }
  }
}

/** Runs the fixed scenario with a given dispatcher and returns metrics.
 *  Time is measured in "moves": one tick == one elevator move (like FastEngine's cost). */
object Simulator {
  val MaxTicksFactor = 8 // safety cap so a bad policy can't loop forever

  def run(scenario: Scenario, dispatch: Dispatcher): RunResult = {
    val cars = (1 to scenario.elevators).map(i => new Car("e" + i, startFloor = 1)).toVector
    // calls still waiting for pickup, keyed by (carIndex, floor)
    val waiting = mutable.Map.empty[(Int, Int), mutable.Queue[Call]]
    val byTick  = scenario.calls.groupBy(_.requestTick)
    val waits   = mutable.ArrayBuffer.empty[Int]
    var served  = 0

    val maxTick = scenario.requests * MaxTicksFactor + scenario.floors
    var tick = 0
    while (tick <= maxTick && served < scenario.calls.size) {
      byTick.getOrElse(tick, Vector.empty).foreach { call =>
        val idx = dispatch.assign(cars, call)
        cars(idx).targets += call.floor
        val q = waiting.getOrElseUpdate((idx, call.floor), mutable.Queue.empty)
        q.enqueue(call)
      }
      cars.indices.foreach { idx =>
        val car = cars(idx)
        val stopped = car.step()
        if (stopped) {
          val key = (idx, car.floor)
          waiting.get(key).foreach { q =>
            while (q.nonEmpty) {
              val c = q.dequeue()
              waits += (tick - c.requestTick)
              served += 1
            }
          }
          waiting.remove(key)
          car.targets -= car.floor
        }
      }
      tick += 1
    }
    val floors = cars.map(_.floorsTravelled).sum
    RunResult(waits.toVector, served, scenario.calls.size, floors)
  }
}
