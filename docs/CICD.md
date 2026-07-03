# CI / CD

Two GitHub Actions workflows tailored to this polyglot, event-sourced system:

| Workflow | File | Trigger | Does |
|----------|------|---------|------|
| **Build & Test** | `.github/workflows/ci.yml` | push + PR to `main` | compile + test the Maven reactor and the Rust console; build images on PRs |
| **Release & Deploy** | `.github/workflows/cd.yml` | after Build & Test passes on `main` (or manual) | push images to GHCR, roll them onto the cluster |

Build & Test always runs and **gates** Release & Deploy — a red build never ships.

```mermaid
flowchart TB
    push["push / PR → main"] --> bt["Build &amp; Test"]

    subgraph bt_jobs["Build &amp; Test jobs"]
        jvm["Scala/Java · Maven<br/>validate → build+unit → Testcontainers IT"]
        rust["Rust console · Cargo<br/>fmt → clippy → test → release build"]
        img["Docker images build<br/>(PR only, no push)"]
    end
    bt --> jvm & rust & img

    bt -->|success on main| rd["Release &amp; Deploy"]
    subgraph rd_jobs["Release &amp; Deploy jobs"]
        pub["Publish images → GHCR<br/>tag = sha-&lt;12&gt; + latest"]
        dep["Deploy → Kubernetes<br/>RBAC/config → backends → app/api → rollout"]
    end
    rd --> pub --> dep --> k8s[("cluster")]
```

---

## Build & Test (`ci.yml`)

Runs on every push and PR to `main`. No setup — works today. Three jobs:

### `jvm` — Scala/Java · Maven
Temurin 21 with Maven cache, then staged for clear signal (you see *which* stage
broke, not just "verify failed"):

1. **Validate reactor & dependency convergence** — `./mvnw validate` runs the
   enforcer `requireUpperBoundDeps` rule across all 12 modules.
2. **Build & unit tests** — `./mvnw install -DskipITs` compiles Scala 3
   (`elevator-common-*`) + `elevator-app` + `elevator-api` and runs the surefire
   unit tests (logic, strategy, event evolution, actor recovery, serialization).
   Integration tests are skipped here.
3. **Integration tests (Testcontainers)** — `./mvnw verify -Dsurefire.skip=true`
   runs only the failsafe `ElevatorStateFlowIT`, which boots Spring and spins up
   Postgres + Kafka in Docker. `ubuntu-latest` provides the Docker daemon, so this
   needs no extra setup.
4. Test reports (`*-reports/*.xml`) are uploaded as an artifact.

### `rust` — Rust console · Cargo
Installs `librdkafka-dev` (rdkafka links it), then `cargo fmt --check`,
`cargo clippy -D warnings`, `cargo test`, and a `--release` build (the demo and
the pre-commit itest run the release binary). The Maven build never compiles the
console — it is behind the `-Pconsole` profile — so **CI is the only gate** for
the Rust code.

> `fmt --check` and `clippy -D warnings` are strict. Formatting drift or warnings
> fail the job until a one-time `cargo fmt` / clippy cleanup. That is the gate
> working, not a pipeline bug.

### `images` — Docker images build (PR only)
On pull requests, builds both `elevator-app` and `elevator-api` images with
`push: false` (matrix, buildx + GHA cache) to catch Dockerfile regressions before
merge. On `main`, publishing is Release & Deploy's job instead. The Dockerfiles
build with `-DskipTests` because the same commit's tests already ran in `jvm`.

---

## Release & Deploy (`cd.yml`)

Triggers via `workflow_run` **after Build & Test succeeds on `main`** (or manually
from the Actions tab). Two jobs.

### `publish` — Publish images → GHCR
Builds both images and pushes them to **GHCR** (`ghcr.io/<owner>/elevator-app`
and `-api`), tagged with the commit SHA (`sha-<12>`) and `latest`. Uses the
built-in `GITHUB_TOKEN` — **no secret to configure.** Works the moment the file
is on `main`.

### `deploy` — Deploy → Kubernetes (Pekko cluster)
Applies manifests in the order the cluster actually needs, then pins the image to
this commit and waits for the rollout:

1. **RBAC, config & DB schema** — `rbac.yaml` (Pekko bootstrap lists sibling pods
   via the k8s API), `configmap.yaml` (fast/slow mode + Kafka wiring),
   `postgres-init.yaml` (the DDL ConfigMap the StatefulSet mounts).
2. **Stateful backends** — `postgres.yaml` + `kafka.yaml`, then
   `kubectl rollout status` on both. The event journal and Kafka must be reachable
   before the app cluster forms and starts consuming.
3. **App & API** — apply, then `kubectl set image ...:sha-<12>` so the running
   pods use the image built from this exact commit.
4. **Wait for rollout** — generous timeout because the rolling update is
   `maxUnavailable: 0` and the app's startup probe tolerates cluster-formation +
   journal-recovery time.

### Deploy target: local kind via a self-hosted runner

The cluster is a local **kind** (`https://127.0.0.1:<port>`), which GitHub's
cloud runners cannot reach. So the `deploy` job runs `runs-on: self-hosted` — an
Actions runner **on the kind host** — while `publish` stays on cloud runners.

Already set up:
- ✅ **`dev` environment** created.
- ✅ **`KUBECONFIG` secret** (base64 of `kubectl config view --raw --minify
  --flatten`) stored in that environment. The deploy job decodes it to a
  job-local temp file (`$RUNNER_TEMP/kubeconfig`) — it never touches the runner's
  own `~/.kube/config`.
- ✅ **Self-hosted runner** `kind-host` at `../.actions-runner` (one level
  **above** the repo, in the elevator project dir — so it stays out of git and
  the Docker build context; all its `_work`/`_diag`/`_temp` live there), managed
  by a **no-sudo user systemd service** `~/.config/systemd/user/actions-runner.service`
  (`active` + `enabled`, `Restart=always`). Manage with
  `systemctl --user status|restart|stop actions-runner` and
  `journalctl --user -u actions-runner -f`.

To recreate the runner (e.g. on a fresh machine), needs `kubectl` + `docker` on
its PATH:

```bash
# 1) registration token
TOKEN=$(gh api -X POST repos/TwistedLady/elevator-system/actions/runners/registration-token -q .token)

# 2) download + configure (pin the current version from Settings → Actions → Runners)
VER=$(gh api repos/actions/runner/releases/latest -q .tag_name | sed 's/^v//')
mkdir -p ../.actions-runner && cd ../.actions-runner
curl -sL -o runner.tar.gz "https://github.com/actions/runner/releases/download/v${VER}/actions-runner-linux-x64-${VER}.tar.gz"
tar xzf runner.tar.gz
./config.sh --url https://github.com/TwistedLady/elevator-system --token "$TOKEN" --name kind-host --labels self-hosted --unattended --replace

# 3) run as a no-sudo user service (what this repo uses)
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/actions-runner.service <<'UNIT'
[Unit]
Description=GitHub Actions self-hosted runner (kind-host)
After=network-online.target
Wants=network-online.target
[Service]
WorkingDirectory=/home/twist/repo/elevator/.actions-runner
ExecStart=/home/twist/repo/elevator/.actions-runner/run.sh
Restart=always
RestartSec=5
[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now actions-runner

# alternatives: ./run.sh (foreground), or a system service via
# sudo ./svc.sh install <user> && sudo ./svc.sh start
```

Notes:
- The `KUBECONFIG` secret points at `127.0.0.1:<port>`; it only resolves from the
  same host, which is exactly where the self-hosted runner lives. If you delete
  and recreate the kind cluster, the port changes — refresh the secret with
  `kubectl config view --raw --minify --flatten | base64 -w0 | gh secret set KUBECONFIG --env dev`.
- Optionally add a **required reviewer** to the `dev` environment
  (Settings → Environments → dev) so each deploy waits for a click.
- Until a runner is online, `publish` still updates GHCR and the `deploy` job
  simply **queues** (it does not fail) waiting for a self-hosted runner.
- The user service starts with your login session. To keep it running across a
  **reboot without logging in**, enable lingering once (the only sudo needed):
  `sudo loginctl enable-linger twist`. Not enabled yet — without it the runner
  comes up when you next log in.

### Private images (optional)

The GHCR packages are currently **public** (repository-scoped packages inherit
the public repo's visibility), so no pull secret is needed. If you make them
**private**, add a `GHCR_PULL_TOKEN` environment secret — a classic PAT with
`read:packages` (not the short-lived `GITHUB_TOKEN`, which expires when the run
ends). CD then creates a `ghcr-pull` docker-registry secret, which both
deployments already reference via `imagePullSecrets`. When `GHCR_PULL_TOKEN` is
unset that step is skipped and the missing secret is harmless for public images.

---

## Why the manifests still say `image: …:local`

`k8s/app.yaml` / `k8s/api.yaml` keep `image: elevator-app:local` so the local
**kind** demo (which `kind load`s the `:local` images) keeps working. CD does not
edit the files — it overrides the image at deploy time with `kubectl set image`
to the exact GHCR SHA. Local dev and cloud deploy never fight over the manifest.

## Known follow-ups

- **Terraform drift** — `terraform/` diverges from `k8s/` (1 vs 2 replicas,
  missing StatefulSet/RBAC/probes). `k8s/` is authoritative and is what CD
  applies; the terraform path is not wired into CD.
- **Secrets** — the Postgres password is still in a ConfigMap; move it to a
  `Secret` before any real deploy.
