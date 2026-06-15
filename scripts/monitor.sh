#!/usr/bin/env bash
# Smart console monitor: an animated chart of the elevators.
#   Y axis = floors, X axis = elevators (the dashboard the project sketched, in your terminal).
#
# Usage:
#   scripts/monitor.sh                 # auto-discover every elevator the API knows about
#   scripts/monitor.sh lift-a lift-b   # watch a fixed set
#
# Env:
#   MAX_FLOOR=10   top floor to draw           INTERVAL=1   seconds between frames
#   FRAMES=0       0 = run forever, N = N then exit (handy for non-interactive use)
#
# Then, in another terminal:  scripts/demo.sh lift-a 7
# Ctrl-C to quit.
set -uo pipefail

API="${API:-http://localhost:8080}"
MAX_FLOOR="${MAX_FLOOR:-10}"
INTERVAL="${INTERVAL:-1}"
FRAMES="${FRAMES:-0}"
COLW=9                       # inner width of each elevator column
FIXED=("$@")                 # explicit elevators, if any

# Build stamp: current commit of the elevator-system rebuild repo (auto-updates as it grows).
SELF="$(cd "$(dirname "$0")" && pwd)"
BUILD="${BUILD:-$(git -C "$SELF/.." rev-parse --short HEAD 2>/dev/null || echo dev)}"

ESC=$'\033'
TTY=0; [ -t 1 ] && TTY=1
if [ "$TTY" = 1 ]; then
  C_MOVE=$'\033[1;32m'; C_STOP=$'\033[1;36m'; C_DIM=$'\033[2;37m'; C_HEAD=$'\033[1m'; C_RST=$'\033[0m'
else
  C_MOVE=""; C_STOP=""; C_DIM=""; C_HEAD=""; C_RST=""
fi

# repeat the box-drawing dash $1 times (tr can't substitute multibyte chars)
hr() { local n="$1" out=''; while (( n-- > 0 )); do out+='─'; done; printf '%s' "$out"; }

# center $1 within width $2 (counts unicode code points)
center() {
  local s="$1" w="$2" len=${#1}
  (( len >= w )) && { printf '%s' "${s:0:w}"; return; }
  local l=$(( (w-len)/2 )) r
  r=$(( w-len-l ))
  printf '%*s%s%*s' "$l" '' "$s" "$r" ''
}

discover() {
  curl -s "$API/api/elevator" 2>/dev/null \
    | grep -oE '"elevatorName":"[^"]*"' | sed -E 's/.*:"([^"]*)"/\1/' | sort -u
}

field() { grep -oE "\"$2\":\"?[^\",}]*\"?" <<<"$1" | head -1 | sed -E 's/.*:"?([^"]*)"?$/\1/'; }

frame=0
while :; do
  if [ "${#FIXED[@]}" -gt 0 ]; then names=("${FIXED[@]}"); else mapfile -t names < <(discover); fi

  # fetch current state for each elevator
  declare -A FL DR MO
  for n in "${names[@]}"; do
    s="$(curl -s "$API/api/elevator/$n" 2>/dev/null)"
    FL[$n]="$(field "$s" floor)"; DR[$n]="$(field "$s" direction)"; MO[$n]="$(field "$s" motion)"
  done

  # ---- build one frame into $buf ----
  buf="${C_HEAD} Elevator monitor${C_RST}   $API   floors ${MAX_FLOOR}..0   (Ctrl-C)"$'\n'
  buf+="${C_STOP} ▣ build ${BUILD}${C_RST}   ${C_DIM}$(date '+%H:%M:%S')${C_RST}"$'\n\n'

  if [ "${#names[@]}" -eq 0 ]; then
    buf+="   (no elevators yet — order one:  scripts/demo.sh lift-a 7)"$'\n'
  else
    for (( r=MAX_FLOOR; r>=0; r-- )); do
      line=$(printf '%3d %s' "$r" '│')
      for n in "${names[@]}"; do
        f="${FL[$n]:-}"
        if [ "$f" = "$r" ]; then
          case "${MO[$n]:-}|${DR[$n]:-}" in
            MOVING\|UP)   glyph="[▲]"; col="$C_MOVE" ;;
            MOVING\|DOWN) glyph="[▼]"; col="$C_MOVE" ;;
            *)            glyph="[●]"; col="$C_STOP" ;;
          esac
          line+="${col}$(center "$glyph" "$COLW")${C_RST}│"
        else
          line+="${C_DIM}$(center '·' "$COLW")${C_RST}│"
        fi
      done
      buf+="$line"$'\n'
    done
    # baseline + name headers + status line
    base="    └"; for n in "${names[@]}"; do base+="$(hr "$COLW")┴"; done
    buf+="${base%┴}┘"$'\n'
    hdr="    "; for n in "${names[@]}"; do hdr+=" ${C_HEAD}$(center "$n" "$COLW")${C_RST}"; done
    buf+="$hdr"$'\n\n'
    for n in "${names[@]}"; do
      buf+="   ${C_HEAD}${n}${C_RST}: floor ${FL[$n]:-?}  ${DR[$n]:-?}  ${MO[$n]:-?}"$'\n'
    done
  fi

  # ---- render ----
  if [ "$TTY" = 1 ]; then printf '%s' "${ESC}[H${ESC}[2J${buf}"; else printf '%s\n' "$buf"; fi
  unset FL DR MO; declare -A FL DR MO

  frame=$((frame+1))
  [ "$FRAMES" -gt 0 ] && [ "$frame" -ge "$FRAMES" ] && break
  sleep "$INTERVAL"
done
