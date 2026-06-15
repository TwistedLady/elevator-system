#!/usr/bin/env bash
# Hot-reloading wrapper around monitor.sh.
#
# It re-invokes monitor.sh as a fresh process for every frame, so any edit to
# monitor.sh takes effect on the NEXT tick — you can keep this running while the
# script is being changed underneath you, uninterrupted. If an edit briefly breaks
# the script, this loop just shows the error and recovers on the following frame.
#
# Usage: scripts/monitor-watch.sh [elevatorName ...]      (same args as monitor.sh)
# Env:   INTERVAL=1   seconds between frames
# Ctrl-C to quit.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
INTERVAL="${INTERVAL:-1}"

cleanup() { tput cnorm 2>/dev/null || true; printf '\n'; exit 0; }
trap cleanup INT TERM

tput civis 2>/dev/null || true   # hide cursor for smoother animation

while true; do
  # FRAMES=1: render exactly one frame from the CURRENT file on disk, then exit.
  FRAMES=1 INTERVAL="$INTERVAL" bash "$HERE/monitor.sh" "$@" || true
  sleep "$INTERVAL"
done
