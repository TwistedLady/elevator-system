package pl.feelcodes.elevator.optimizer

import scala.util.Random

/** Result of a search: the best weights found and how much work it took. */
case class Outcome(name: String, best: Weights, bestF: Double, evaluations: Int)

/** Every method optimises the SAME target f, so they can be compared fairly.
 *  `budget` = how many times we are allowed to call f (fair comparison unit). */
trait Optimizer {
  def name: String
  def search(f: Weights => Double, budget: Int, rng: Random): Outcome
}

object Space {
  val lo = 0.0
  val hi = 5.0 // weights live in [0, 5]^4
  def clamp(x: Double): Double = math.max(lo, math.min(hi, x))
  def random(rng: Random): Weights =
    Weights.fromArray(Array.fill(Weights.size)(lo + rng.nextDouble() * (hi - lo)))
  def perturb(w: Weights, step: Double, rng: Random): Weights =
    Weights.fromArray(w.toArray.map(v => clamp(v + rng.nextGaussian() * step)))
}

/** Monte Carlo / random search: just try many random points, keep the best.
 *  The dumb baseline that proves the smart methods actually help. */
class RandomSearch extends Optimizer {
  val name = "RandomSearch"
  def search(f: Weights => Double, budget: Int, rng: Random): Outcome = {
    var best = Space.random(rng); var bestF = f(best)
    var used = 1
    while (used < budget) {
      val cand = Space.random(rng); val cf = f(cand); used += 1
      if (cf < bestF) { best = cand; bestF = cf }
    }
    Outcome(name, best, bestF, used)
  }
}

/** Hill climbing: greedily step to a better neighbour; never accept worse.
 *  Simple and fast, but gets trapped in local optima. */
class HillClimbing(step: Double = 0.6) extends Optimizer {
  val name = "HillClimbing"
  def search(f: Weights => Double, budget: Int, rng: Random): Outcome = {
    var cur = Space.random(rng); var curF = f(cur)
    var used = 1
    while (used < budget) {
      val cand = Space.perturb(cur, step, rng); val cf = f(cand); used += 1
      if (cf < curF) { cur = cand; curF = cf } // greedy: only downhill
    }
    Outcome(name, cur, curF, used)
  }
}

/** Simulated annealing: sometimes accept a WORSE step, less and less over time.
 *  The classic probabilistic method — escapes local optima hill climbing gets stuck in. */
class SimulatedAnnealing(step: Double = 0.6, t0: Double = 5.0) extends Optimizer {
  val name = "SimulatedAnnealing"
  def search(f: Weights => Double, budget: Int, rng: Random): Outcome = {
    var cur = Space.random(rng); var curF = f(cur)
    var best = cur; var bestF = curF
    var used = 1
    while (used < budget) {
      val progress = used.toDouble / budget
      val t = t0 * (1.0 - progress) + 1e-6 // temperature cools to ~0
      val cand = Space.perturb(cur, step, rng); val cf = f(cand); used += 1
      val delta = cf - curF
      if (delta < 0 || rng.nextDouble() < math.exp(-delta / t)) { cur = cand; curF = cf }
      if (curF < bestF) { best = cur; bestF = curF }
    }
    Outcome(name, best, bestF, used)
  }
}

/** Genetic algorithm: a population of weight-vectors; keep the best,
 *  blend (crossover) and mutate to make the next generation. */
class GeneticAlgorithm(pop: Int = 12, mutation: Double = 0.5) extends Optimizer {
  val name = "GeneticAlgorithm"
  def search(f: Weights => Double, budget: Int, rng: Random): Outcome = {
    var population = Vector.fill(pop)(Space.random(rng))
    var scored = population.map(w => (w, f(w)))
    var used = pop
    def tournament(): Weights = {
      val a = scored(rng.nextInt(scored.size)); val b = scored(rng.nextInt(scored.size))
      if (a._2 < b._2) a._1 else b._1 // lower f wins
    }
    def crossover(x: Weights, y: Weights): Weights = {
      val a = x.toArray; val b = y.toArray
      Weights.fromArray(Array.tabulate(Weights.size)(i => (a(i) + b(i)) / 2.0)) // blend
    }
    while (used < budget) {
      val elite = scored.sortBy(_._2).head._1 // elitism: carry the best over
      val children = elite +: Vector.fill(pop - 1) {
        Space.perturb(crossover(tournament(), tournament()), mutation, rng)
      }
      population = children
      scored = population.map { w => used += 1; (w, f(w)) }
    }
    val (best, bestF) = scored.minBy(_._2)
    Outcome(name, best, bestF, used)
  }
}
