# elevator-console (Rust)

A terminal app for the elevator system. Today it tails the `elevator-state` Kafka
topic and shows the latest state of every elevator in a live table; sending orders
to `elevator-commands` is planned next. A counterpart to the Spring `elevator-api`
(HTTP) — same topics, its own consumer group, no Maven involved.

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

Then run the monitor:

```bash
cargo run            # defaults: localhost:9092, topic elevator-state
```

Override via env vars:

```bash
KAFKA_BOOTSTRAP=localhost:9092 STATE_TOPIC=elevator-state cargo run
```

Build a release binary:

```bash
cargo build --release   # -> target/release/elevator-console
```

## Building with Maven

This is a real Maven module (shows in the reactor, targetable with `-pl`), but its
`cargo` build is skipped unless you enable the `console` profile — so a normal build
needs no Rust toolchain. Needs `cargo` on your PATH when enabled.

```bash
mvn package                            # console listed in the tree, cargo skipped
mvn -Pconsole package                  # builds everything incl. `cargo build --release`
mvn -Pconsole -pl elevator-console package   # build ONLY the console
mvn -Pconsole clean                    # also runs `cargo clean`
```

Equivalent without the profile: `mvn -Dcargo.skip=false ...`.
