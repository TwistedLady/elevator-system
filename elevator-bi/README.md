# elevator-bi — Spark mileage BI

A Spark Structured Streaming job that computes each elevator's **mileage** — the total number of
floors it has travelled (`Σ |floor − previousFloor|`) — from the `elevator-state` Kafka topic, and
upserts it into a Postgres `elevator_mileage` table for BI/reporting.

```mermaid
flowchart LR
  kafka(("Kafka<br/>elevator-state"))
  subgraph spark["Spark on Kubernetes (elevator-bi)"]
    driver["driver<br/>(Deployment, client mode)"]
    ex1["executor 1"]
    ex2["executor 2"]
  end
  pg[("Postgres<br/>elevator_mileage")]

  kafka -- "read stream (JSON)" --> ex1
  kafka --> ex2
  ex1 -- "state deltas" --> driver
  ex2 --> driver
  driver -- "foreachBatch upsert" --> pg
```

## Why standalone + Scala 2.12

Spark has **no Scala 3 build** ([SPARK-54150](https://issues.apache.org/jira/browse/SPARK-54150) is
open, no release), and the official Spark images are published for **Scala 2.12 only**. So this is a
**standalone** Maven module (its own pom, NOT in the root reactor) pinned to Scala 2.12. It does not
depend on the Scala 3 `elevator-common`; it reads `elevator-state` by its JSON wire schema, which
keeps the analytics job decoupled from the producer's internal types anyway.

## Layout

| File | Role |
|---|---|
| `Mileage.scala` | Pure fold `Option[MileageState] × Seq[Int] → Option[MileageState]` (unit-tested, no Spark) |
| `kafka/ElevatorStateSchema.scala` | Explicit `StructType` for the `elevator-state` JSON |
| `config/BiConfig.scala` | Env-driven configuration (12-factor) |
| `sink/PostgresMileageSink.scala` | Idempotent JDBC upsert (`CREATE TABLE IF NOT EXISTS` + `ON CONFLICT`) |
| `MileageJob.scala` | The streaming job: Kafka → `flatMapGroupsWithState` per elevator → `foreachBatch` |

## Build & test

```bash
mvn -f elevator-bi/pom.xml package      # compiles (Scala 2.12) + runs ScalaTest + builds the uber jar
```

The uber jar bundles the Kafka source + Postgres driver; Spark itself is `provided` by the runtime image.

## Deploy (full Spark-on-Kubernetes, no operator/Helm)

```bash
scripts/bi-up.sh          # build jar + image, kind-load, apply manifests, wait for driver + executors
scripts/bi-logs.sh        # collect driver + executor logs into logs/bi/
scripts/bi-down.sh        # tear down (add --purge to also drop the table)
```

- The `elevator-mileage` **Deployment** runs the Spark **driver** in *client mode*; it spawns 2
  **executor pods** via the Kubernetes resource manager. The Deployment restarts the driver if it dies.
- **Checkpoint** (stateful streaming state) lives on a `hostPath` shared by driver + executors —
  correct because kind is a **single node**. On a multi-node cluster, point
  `ELEVATOR_BI_CHECKPOINT` / `spark.eventLog.dir` at **S3 (`s3a://`)** or **NFS** instead.

## Logs

Stored three ways (see `conf/log4j2.properties` + `k8s/bi/mileage-driver.yaml`):
1. **stdout** — `kubectl logs` / the driver Deployment.
2. **durable rolling files** — one per pod on the persistent `/opt/elevator-bi/logs` hostPath volume.
3. **Spark event logs** — `file:///checkpoint/spark-events` (job/stage history, History-Server-readable).

`scripts/bi-logs.sh` pulls all three into `logs/bi/`.

## Config (env)

| Var | Default | Meaning |
|---|---|---|
| `ELEVATOR_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka brokers |
| `ELEVATOR_KAFKA_STATE_TOPIC` | `elevator-state` | source topic |
| `ELEVATOR_BI_STARTING_OFFSETS` | `earliest` | first-run offset (checkpoint takes over after) |
| `ELEVATOR_BI_CHECKPOINT` | `file:///checkpoint` | streaming checkpoint dir (shared FS) |
| `ELEVATOR_BI_TRIGGER` | `10 seconds` | micro-batch interval |
| `ELEVATOR_BI_JDBC_URL` | `jdbc:postgresql://postgres:5432/elevator` | sink DB |
| `ELEVATOR_PG_USER` / `ELEVATOR_PG_PASSWORD` | `elevator` / `elevator` | sink creds (demo — use a Secret in prod) |
| `ELEVATOR_BI_TABLE` | `elevator_mileage` | sink table |
