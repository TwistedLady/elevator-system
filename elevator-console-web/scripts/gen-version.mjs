// Writes src/app/version.ts from the repo-root VERSION file, the single source of truth shared
// with the backend and the Rust console. Runs before build/start and after install (see the
// pre/post scripts in package.json), so the generated file always exists and is up to date.
import { readFileSync, writeFileSync } from 'node:fs';

const versionUrl = new URL('../../VERSION', import.meta.url);
const outUrl = new URL('../src/app/version.ts', import.meta.url);

const version = readFileSync(versionUrl, 'utf8').trim();
writeFileSync(
  outUrl,
  `// Generated from the repo-root VERSION file by scripts/gen-version.mjs. Do not edit.\n` +
    `export const APP_VERSION = '${version}';\n`,
);
console.log(`elevator-console-web version: ${version}`);
