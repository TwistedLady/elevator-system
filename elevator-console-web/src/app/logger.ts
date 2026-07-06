// Tiny console logger with a shared prefix, so browser-devtools output is easy to
// filter. Silence everything below `level` (e.g. flip to 'warn' to quiet a demo).

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const ORDER: Record<LogLevel, number> = { debug: 0, info: 1, warn: 2, error: 3 };
const PREFIX = '[web-console]';

let threshold: LogLevel = 'info';

export function setLogLevel(level: LogLevel): void {
  threshold = level;
}

function enabled(level: LogLevel): boolean {
  return ORDER[level] >= ORDER[threshold];
}

export const log = {
  debug: (...args: unknown[]) => enabled('debug') && console.debug(PREFIX, ...args),
  info: (...args: unknown[]) => enabled('info') && console.info(PREFIX, ...args),
  warn: (...args: unknown[]) => enabled('warn') && console.warn(PREFIX, ...args),
  error: (...args: unknown[]) => enabled('error') && console.error(PREFIX, ...args),
};
