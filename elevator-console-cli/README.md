# elevator-console-cli (Rust)

A terminal app for the elevator system. It can **monitor** elevator state, **send** a single call,
and **trigger** a server-side bulk simulation. It talks to the system **only through the
`elevator-api` HTTP edge** (plus `kubectl` in the `itest` log cross-check) — it never touches Kafka
directly.

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
Calls, live state, config, health, version and the simulation are all derived from it:

| Concern | Endpoint |
|---|---|
| send call | `POST {api}/api/call` |
| live state | SSE `GET {api}/api/elevator/stream` (falls back to polling `GET {api}/api/elevator`) |
| config (maxFloor, fleet) | `GET {api}/api/config` |
| health | `GET {api}/actuator/health` |
| version | `GET {api}/api/version` |
| start a simulation | `POST {api}/api/simulate?count=N` → `{runId, count, ids}` |
| simulation progress | `GET {api}/api/simulate/progress?runId=..&size=..` |

## Run

### Monitor — tabbed live dashboard

```bash
cargo run -- monitor
# or point at a remote api:
cargo run -- monitor --api http://my-host:8080
```

A retro multi-tab TUI with exactly **three tabs**, shared with the Elm web console. Switch tabs with
**Tab / Shift-Tab**, quit with **Esc**. One shared header carries the title `🛗 ELEVATOR CONSOLE`, an
**API health** badge, and a **version** badge (console vs. backend). Chart and Trend share one regex
name filter:

| Tab | Input | What it does |
|-----|-------|--------------|
| **① CHART** | filter | Live floor-by-floor grid of every elevator. Regex name filter; Enter clears. |
| **② TREND** | filter | Floor-over-time line chart; same name filter as Chart. |
| **③ SIM**   | `r`   | Press **`r`/`R`** to run a server-side **10,000-call simulation** (`POST /api/simulate`), then poll `GET /api/simulate/progress` every ~2s and draw a **progress bar** split by status (green DONE / yellow in-progress / gray pending) plus a summary (size · calls · orders · done · first/last times), ending in `✓ simulation complete`. |

State arrives over SSE (or polling), so the chart stays up and reconnects across backend restarts.

### Call — send one call

```bash
cargo run -- call --elevator e3 --floor 7
```

### Simulate — server-side bulk load

```bash
cargo run -- simulate                 # POST /api/simulate — the api fires 10k random calls
cargo run -- simulate --count 5000    # optional count (api defaults & caps at 10000)
```

The console just **triggers** the run over HTTP and prints the run id; the api generates the random
calls (random elevator + floor) and publishes them. There are no client-side thread/fleet/floor
flags — the api owns all of that.

### Headless helpers

```bash
cargo run -- watch                # plain-text live view of latest state per elevator
cargo run -- selftest             # one-shot: api health + live state → pass/fail log
cargo run -- itest --count 20     # simulate N calls, poll each to DONE, cross-check kubectl logs
```

## Test

Pure logic (health parsing, latency stats, natural sort, the health/version badges, the
status-split progress bar, state decoding, tab navigation and the name filter) is covered by unit
tests:

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
