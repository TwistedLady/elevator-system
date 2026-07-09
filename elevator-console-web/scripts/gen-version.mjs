// Writes src/generated/version.js from the repo-root VERSION file, the single source of truth
// shared with the backend and the Rust console. Runs before build/start and after install (see the
// pre/post scripts in package.json), so main.js can import it and pass it to Elm as a flag.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';

const versionUrl = new URL('../../VERSION', import.meta.url);
const outDir = new URL('../src/generated/', import.meta.url);
const outUrl = new URL('../src/generated/version.js', import.meta.url);

const version = readFileSync(versionUrl, 'utf8').trim();
mkdirSync(outDir, { recursive: true });
writeFileSync(
  outUrl,
  `// Generated from the repo-root VERSION file by scripts/gen-version.mjs. Do not edit.\n` +
    `export const APP_VERSION = '${version}';\n`,
);
console.log(`elevator-console-web version: ${version}`);
