// Runs `vite build`, retrying a few times so transient Elm package-download blips don't fail the
// build. The Elm compiler fetches its packages from github zipballs on a cold cache; when github
// hiccups, `elm make` aborts. Successfully-downloaded packages are cached in ELM_HOME between
// attempts, so each retry has less to fetch and the build converges. Used by `npm run build`, which
// both the Docker image and the Maven frontend build invoke.
import { spawnSync } from 'node:child_process';

const ATTEMPTS = 3;
const DELAYS_MS = [2000, 5000]; // waited after attempts 1 and 2

// Synchronous sleep — this is a short-lived build script, so blocking is fine and keeps the flow linear.
function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
  // shell:true so the local `vite` bin resolves from node_modules/.bin (npm puts it on PATH).
  const result = spawnSync('vite', ['build'], { stdio: 'inherit', shell: true });
  if (result.status === 0) {
    process.exit(0);
  }
  console.error(`\n[build] vite build failed (attempt ${attempt}/${ATTEMPTS}, exit ${result.status}).`);
  if (attempt < ATTEMPTS) {
    const wait = DELAYS_MS[attempt - 1] ?? 5000;
    console.error(`[build] retrying in ${wait / 1000}s (transient Elm package download?)…`);
    sleep(wait);
  }
}

console.error('[build] vite build failed after all retries.');
process.exit(1);
