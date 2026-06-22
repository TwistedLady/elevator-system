# elevator-console (Rust)

A terminal app for the elevator system. It can **monitor** elevator state, **send**
a single order, and **simulate** a bulk load of orders. A counterpart to the Spring
`elevator-api` (HTTP) — same Kafka topics, its own consumer group.

```
🛗  Elevator monitor — 2 elevator(s)

ELEVATOR          FLOOR  DIRECTION  MOTION
----------------------------------------------
alpha                 3  UP         MOVING
beta                  0  NONE       IDLE
```

## Prerequisites

- Rust toolchain (`rustup` / `cargo`)
- `librdkafka`:
  - Debian/Ubuntu: `sudo apt install librdkafka-dev`
  - macOS: `brew install librdkafka`
  - No system lib? Set `rdkafka = { version = "0.36", features = ["cmake-build"] }`
    in `Cargo.toml` to build it from source (needs `cmake` + a C compiler).

## Run

Start the demo broker (from `elevator-system/`):

```bash
docker compose -p elevator-demo -f docker-compose.demo.yml up -d
```

### Monitor — live building chart + inline ordering

```bash
cargo run -- monitor
```

Shows a live floor-by-floor chart of every elevator. To order without leaving the
monitor, type `<elevator> <floor>` and press Enter:

```
lift-01 7      # send lift-01 to floor 7
lift-02 0      # send lift-02 to the ground floor
sim 60         # fire 60 random orders across the elevators on screen
```

It survives backend/Kafka restarts — the chart stays up and reconnects automatically.

### Order — send one order

```bash
cargo run -- order --elevator alpha --floor 7
```

### Simulate — bulk load

```bash
# 10k orders (default) across alpha,beta,gamma on 4 threads
cargo run --release -- simulate

# crank it: 1,000,000 orders, 8 threads, floors 0..=20
cargo run --release -- simulate --count 1000000 --threads 8 --max-floor 20 \
    --elevators alpha,beta,gamma,delta

# specify how many elevators by number (generates e1..eN, overrides --elevators)
cargo run --release -- simulate --elevator-count 6 --count 500

# or read the fleet from a file (one name per line or comma-separated; '#' = comment)
cargo run --release -- simulate --elevators-file scripts/fleet.txt --count 500
```

Source precedence is `--elevator-count` > `--elevators-file` > `--elevators`. Whatever the
source, names are de-duplicated and natural-sorted (`e1, e2, … e10`) so they stay ordered.

> Use `--release` for the simulator — a debug build is much slower.

Common options: `--brokers` (env `KAFKA_BOOTSTRAP`, default `localhost:9092`),
`--topic` (env `STATE_TOPIC` / `COMMAND_TOPIC`). Run `cargo run -- --help` for all.

Build a release binary:

```bash
cargo build --release   # -> target/release/elevator-console
```

## Building with Maven

This is a real Maven module (shows in the reactor, targetable with `-pl`). Its `cargo`
build is **skipped by default**, so a plain `mvn` build never needs a Rust toolchain —
including IDE/CI builds where `cargo` isn't on the PATH. Opt in to build it:

```bash
mvn package                                  # JVM modules only; cargo step skipped
mvn -Pconsole package                        # also runs cargo build --release
mvn -Pconsole -pl elevator-console package   # build ONLY the console
mvn -Pconsole clean                          # also runs cargo clean
```

Equivalent to the profile: `mvn -Dcargo.skip=false ...`. In IntelliJ, tick the `console`
profile in the Maven panel to build the Rust binary from the IDE (it must find `cargo` —
add `~/.cargo/bin` to the IDE's PATH if needed).
