# Versioning

One version for the whole app, in one file: **`VERSION`** at the repo root (e.g. `1.0.0`).
Every component reads it at **build time**, so a single checkout always produces matching builds.
You never edit a version by hand — **release-please** bumps it from your commit messages.

```mermaid
flowchart LR
  PR["merged PRs<br/>feat: / fix: / feat!:"] -->|release-please| V["VERSION + all module versions"]
  V -->|copy-resources| API["elevator-api<br/>GET /api/version"]
  V -->|build.rs → APP_VERSION| CLI["elevator-console-cli"]
  V -->|gen-version.mjs → version.ts| WEB["elevator-console-web"]
  V -->|revision property| MVN["Maven reactor (all modules)"]
  V -->|Chart.yaml| HELM["Helm chart / images :X.Y.Z"]
```

## Who reads VERSION, and how

| Component | Build wiring | Runtime |
|---|---|---|
| `elevator-api` | `maven-resources-plugin` copies `VERSION` onto the classpath | `VersionController` serves `GET /api/version` |
| `elevator-console-cli` | `build.rs` bakes it in as `env!("APP_VERSION")` | `--version`, `version` subcommand |
| `elevator-console-web` | `scripts/gen-version.mjs` writes `src/app/version.ts` | `APP_VERSION`, shown in the top bar |
| Maven reactor | `<revision>` property in the root `pom.xml`; every module is `${revision}` | jar/image names are `…-X.Y.Z` |
| Helm | `Chart.yaml` `version` / `appVersion` + subchart pins | chart + images tagged `X.Y.Z` |

## How the version is chosen — automatically

Commits follow **Conventional Commits**. Because the repo squash-merges, the **PR title** is the
commit release-please reads (a CI check enforces the format):

| PR title | version effect (pre-1.0) |
|---|---|
| `fix: …` | patch — `0.0.1 → 0.0.2` |
| `feat: …` | minor — `0.0.1 → 0.1.0` |
| `feat!: …` or `BREAKING CHANGE:` | minor while < 1.0.0 (`bump-minor-pre-major`) |
| `chore: … / docs: … / refactor: …` | no release on their own |

## Releasing

You do **not** touch version numbers. The flow is:

1. Merge normal PRs to `main` (conventional titles).
2. release-please keeps a **release PR** open ("chore: release X.Y.Z") that bumps `VERSION` +
   every module version + the changelog. Review it like any PR.
3. **Merge the release PR** → release-please tags `vX.Y.Z` and creates the GitHub Release →
   `cd.yml` publishes images `ghcr.io/…:X.Y.Z` and deploys the chart pinned to that version.

Config: `release-please-config.json` + `.release-please-manifest.json`. Version-bearing lines carry
an `x-release-please-version` marker so release-please updates them without reformatting the file
(JSON `package.json` is updated by jsonpath — no marker possible in JSON).

## Enforced rules

- **Lockstep** — `ci.yml` fails the build if any module version ≠ `VERSION`.
- **Conventional PR titles** — `pr-title.yml` (the release depends on them).
- **Tag == VERSION** — `cd.yml` refuses to publish a tag that doesn't match `VERSION`.
- **Immutable releases** — a published `:X.Y.Z` image is never overwritten; `:latest` tracks the
  newest release only.

## One-time setup

Add a repo secret **`RELEASE_PLEASE_TOKEN`** (a PAT with `repo` + `workflow` scope, or a
fine-grained token with contents + pull-requests write). It lets the tag release-please pushes
trigger `cd.yml`. Without it the release is still created, but you start the deploy manually
(`cd.yml` → *Run workflow* on the tag).
