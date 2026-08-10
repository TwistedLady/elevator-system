package pl.feelcodes.elevator.optimizer

import scala.util.Random

/** The lab: one fixed scenario, one target function, many search methods — compared.
 *  Run:  ./run.sh   (or override:  ./run.sh <elevators> <floors> <requests> <budget>) */
object Lab {
  def main(args: Array[String]): Unit = {
    val elevators = arg(args, 0, 10)
    val floors    = arg(args, 1, 15)
    val requests  = arg(args, 2, 10000)
    val budget    = arg(args, 3, 120) // how many f(x) evaluations each method may spend

    val scenario = Scenario(elevators, floors, requests, seed = 42L)
    val target = new Target(scenario)

    println(s"Scenario: $elevators elevators, $floors floors, $requests requests (seed 42, fixed)")
    println(s"Budget per method: $budget evaluations of f(x)\n")

    val (baseF, baseR) = target.evaluate(Weights.nearest)
    println(f"Baseline 'nearest car wins':  f=$baseF%10.2f  avgWait=${baseR.avgWait}%6.2f  p95=${baseR.p95Wait}%4d  unfinished=${baseR.unfinished}%d\n")

    val methods: Vector[Optimizer] = Vector(
      new RandomSearch,
      new HillClimbing(),
      new SimulatedAnnealing(),
      new GeneticAlgorithm()
    )

    val results = methods.map { m =>
      val rng = new Random(1L) // same start seed for every method = fair comparison
      val started = System.nanoTime()
      val o = m.search(target.apply, budget, rng)
      val ms = (System.nanoTime() - started) / 1000000
      (o, ms)
    }

    println("Method               bestF        avgWait   p95   unfin   evals   ms")
    println("-" * 72)
    results.sortBy(_._1.bestF).foreach { case (o, ms) =>
      val (_, r) = target.evaluate(o.best)
      println(f"${o.name}%-18s ${o.bestF}%10.2f ${r.avgWait}%9.2f ${r.p95Wait}%5d ${r.unfinished}%6d ${o.evaluations}%6d ${ms}%5d")
    }

    val champ = results.map(_._1).minBy(_.bestF)
    println(f"\nBest method: ${champ.name}  ->  weights = ${fmt(champ.best)}")
    println(f"Improvement over baseline: ${(baseF - champ.bestF) / baseF * 100}%.1f%% lower f")
  }

  private def fmt(w: Weights): String =
    f"[distance=${w.distance}%.2f load=${w.load}%.2f wrongDir=${w.wrongDir}%.2f busy=${w.busy}%.2f]"

  private def arg(args: Array[String], i: Int, default: Int): Int =
    if (args.length > i) args(i).toInt else default
}
