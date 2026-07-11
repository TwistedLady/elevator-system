package pl.feelcodes.elevator.optimizer

/** The decision VARIABLES (x) we optimise.
 *  Each weight scales one feature of "how good is this car for this call". */
case class Weights(distance: Double, load: Double, wrongDir: Double, busy: Double) {
  def toArray: Array[Double] = Array(distance, load, wrongDir, busy)
}
object Weights {
  val size = 4
  def fromArray(a: Array[Double]) = Weights(a(0), a(1), a(2), a(3))
  /** A plain, human baseline: "nearest car wins", nothing else matters. */
  val nearest = Weights(distance = 1.0, load = 0.0, wrongDir = 0.0, busy = 0.0)
}

trait Dispatcher {
  /** Pick, GREEDILY, the index of the car that should serve this call right now. */
  def assign(cars: Vector[Car], call: Call): Int
}

/** Assign each call to the car with the LOWEST weighted score.
 *  The greedy step; the weights are what the probabilistic methods search for. */
class WeightedDispatcher(w: Weights) extends Dispatcher {
  def assign(cars: Vector[Car], call: Call): Int = {
    var best = 0
    var bestScore = Double.MaxValue
    var i = 0
    while (i < cars.size) {
      val s = score(cars(i), call)
      if (s < bestScore) { bestScore = s; best = i }
      i += 1
    }
    best
  }

  private def score(car: Car, call: Call): Double = {
    val distance = math.abs(car.floor - call.floor).toDouble
    val load     = car.load.toDouble
    val movingUp = car.dir == Up
    val callAbove = call.floor >= car.floor
    val wrongDir = if (!car.idle && movingUp != callAbove) 1.0 else 0.0
    val busy     = if (car.idle) 0.0 else 1.0
    w.distance * distance + w.load * load + w.wrongDir * wrongDir + w.busy * busy
  }
}

/** The TARGET FUNCTION f(x): run the fixed scenario with these weights,
 *  fold the metrics into ONE number. Lower is better. */
class Target(scenario: Scenario) {
  val UnfinishedPenalty = 1000.0 // any unserved call is very bad
  val EnergyWeight      = 0.01   // gently prefer less floor travel

  def apply(w: Weights): Double = {
    val r = Simulator.run(scenario, new WeightedDispatcher(w))
    r.avgWait +
      0.5 * r.p95Wait +
      UnfinishedPenalty * r.unfinished +
      EnergyWeight * r.floorsTravelled
  }

  /** Same run, but keep the details — for reporting the winner. */
  def evaluate(w: Weights): (Double, RunResult) = {
    val r = Simulator.run(scenario, new WeightedDispatcher(w))
    val f = r.avgWait + 0.5 * r.p95Wait + UnfinishedPenalty * r.unfinished + EnergyWeight * r.floorsTravelled
    (f, r)
  }
}
