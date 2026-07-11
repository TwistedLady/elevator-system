# elevator-optimizer (scratch lab)

A standalone playground for **greedy + probabilistic optimization** (*zachłanne metody
probabilistyczne*) on the elevator dispatch problem. **Not wired into the app** — it mirrors the real
domain (SCAN routing, per-move cost) in a fast in-memory simulator so a search can run thousands of
evaluations in seconds.

## The idea

| Concept | Here |
|---|---|
| Fixed scenario | `Scenario(elevators, floors, requests, seed)` — same calls every run |
| Variables `x` | `Weights(distance, load, wrongDir, busy)` — how a car is scored for a call |
| Greedy step | `WeightedDispatcher` — each call goes to the lowest-score car |
| Target `f(x)` | `Target` — run scenario → `avgWait + 0.5·p95 + 1000·unfinished + 0.01·floors` (minimise) |
| Methods | RandomSearch (Monte Carlo), HillClimbing, SimulatedAnnealing, GeneticAlgorithm |

The **app never knows** about the search — a method only picks `Weights`; swapping in the real system
later means feeding those weights to a real dispatcher and reading `f` from Postgres/Spark instead.

## Run

```bash
./run.sh                 # default: 10 elevators, 15 floors, 10000 requests, budget 120
./run.sh 3 6 200 40      # elevators floors requests budget
```

Prints a baseline ("nearest car wins") and a table comparing every method by best `f`, avg wait, p95,
unfinished, and evaluations used.
