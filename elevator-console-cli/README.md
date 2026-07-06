# elevator-console-cli (Rust)

A terminal app for the elevator system. It can **monitor** elevator state, **send** a single order,
and **simulate** a bulk load. It talks to the system **only through the `elevator-api` HTTP edge**
(plus `kubectl` and `git` for infra) — it never touches Kafka directly.

```
🛗  Elevator monitor — 2 elevator(s)

ELEVATOR          FLOOR  DIRECTION  MOTION
----------------------------------------------
e1                    3  UP         MOVING
e2                    0  NONE       IDLE
```

## Prerequisites

- To **build**: a Rust toolchain (`rustup` / `cargo`). That's it — no `librdkafka`, no system libs.
- To **install a prebuilt** binary/`.deb`/image: nothing (see [Install](#install)).
- At **runtime**: a reachable `elevator-api` (default `https://localhost:8080`).

## Install

Pick whichever fits — all four give you an `elevator-console-cli` on your PATH.

```bash
# 1) From source with cargo (installs to ~/.cargo/bin)
cargo install --path .

# 2) Build + install script (installs to ~/.local/bin, override with PREFIX=)
./install.sh
PREFIX=/usr/local ./install.sh          # system-wide (may need sudo)

# 3) Debian / Ubuntu package
cargo install cargo-deb && cargo deb     # -> target/debian/elevator-console-cli_*.deb
sudo dpkg -i target/debian/elevator-console-cli_*.deb

# 4) Docker image (headless subcommands; use -it for the TUI)
docker build -t elevator-console-cli .
docker run --rm elevator-console-cli --help
```

Prebuilt binaries, checksums and a `.deb` are attached to each **`console-v*`** GitHub release
(built by `.github/workflows/console-release.yml`). The `Makefile` wraps all of the above —
`make help`.

## Config

Everything hangs off one flag: **`--api`** (env **`ELEVATOR_API`**, default `https://localhost:8080`).
Orders, live state, order status and health are all derived from it:

| Concern | Endpoint |
|---|---|
| send order | `POST {api}/api/order` |
| live state | SSE `GET {api}/api/elevator/stream` (falls back to polling `GET {api}/api/elevator`) |
| order status | `GET {api}/api/order/{tag}` |
| health | `GET {api}/actuator/health` |

## Run

### Monitor — tabbed live dashboard

```bash
cargo run -- monitor
# or point at a remote api:
cargo run -- monitor --api http://my-host:8080
```

A retro multi-tab TUI. Switch tabs with **Tab / Shift-Tab**, quit with **Esc**. The header shows the
active app mode, the clock, and the console's **git branch@sha**. Each tab has its own input line:

| Tab | Input | What it does |
|-----|-------|--------------|
| **① Chart**  | filter | Live floor-by-floor grid of every elevator. Regex name filter; Enter clears. |
| **② Trend**  | filter | Floor-over-time line chart; same name filter as Chart. |
| **③ Order**  | `<elevator> <floor>` | Send one order, e.g. `e3 7`. |
| **④ Sim**    | count  | Fire N random orders (e.g. `300`) with a **progress bar** (paced for small runs). |
| **⑤ Health** | —      | Actuator health; clear "waiting for backend" banner when the api is unreachable. |
| **⑥ Logs**   | filter | Tails `elevator-app` / `elevator-api` logs (←/→ to switch); regex filter. |
| **⑦ K8s**    | keys   | `f` fast · `s` slow · `r` restart — swaps the app configmap / rolls the pod (via `kubectl`). |
| **⑧ Test**   | `r`    | Runs the integration test and shows the report. |

State arrives over SSE (or polling), so the chart stays up and reconnects across backend restarts.

### Order — send one order

```bash
cargo run -- order --elevator e3 --floor 7
```

### Simulate — bulk load

```bash
cargo run --release -- simulate                       # 10k orders across e1..e10, 4 threads
cargo run --release -- simulate --count 100000 --threads 8 --max-floor 20
cargo run --release -- simulate --elevator-count 6 --count 500
cargo run --release -- simulate --elevators-file fleet.example.txt --count 500
```

Source precedence: `--elevator-count` > `--elevators-file` > `--elevators`. Names are de-duplicated
and natural-sorted. Use `--release` — a debug build is much slower.

### Headless helpers

```bash
cargo run -- watch                # plain-text live view of latest state per elevator
cargo run -- selftest             # one-shot: api health + live state → pass/fail log
cargo run -- itest --count 20     # fire orders, poll status, cross-check kubectl logs
```

## Test

Pure logic (fleet/tag distribution, health parsing, latency stats, input parsing, the PRNG, view
navigation) is covered by unit tests:

```bash
cargo test          # unit tests
make check          # what CI runs: fmt --check + clippy -D warnings + tests
```

## Build & package

```bash
cargo build --release   # -> target/release/elevator-console-cli (stripped, ~2.8 MB)
make dist               # stripped binary + sha256 in dist/
make deb                # Debian package (needs: cargo install cargo-deb)
make docker             # container image
```

The release profile (`opt-level="z"`, LTO, one codegen unit, `panic="abort"`, `strip`) plus trimmed
dependency features roughly **halve** the binary versus a default `--release` build.

## Building with Maven

A real Maven module; its `cargo` build is **skipped by default**, so a plain `mvn` build never needs
a Rust toolchain. Opt in:

```bash
mvn package                                  # JVM modules only; cargo step skipped
mvn -Pconsole package                        # also runs cargo build --release
mvn -Pconsole -pl elevator-console-cli package   # build ONLY the console
```

Equivalent: `mvn -Dcargo.skip=false ...`. In IntelliJ, tick the `console` profile in the Maven panel
(it must find `cargo` — add `~/.cargo/bin` to the IDE's PATH if needed).
