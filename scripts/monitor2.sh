#!/usr/bin/env bash
# Smart console monitor v2 — animated elevator chart with a live SYSTEM HEADER.
#
# Just run it directly (no wrapper needed):
#   scripts/monitor2.sh                 # auto-discover every elevator
#   scripts/monitor2.sh lift-a lift-b   # watch a fixed set
#
# The header updates EVERY frame:
#   build <hash>  = current commit of elevator-system (changes when I commit)
#   clock + frame = proof the view is live
#
# Env: MAX_FLOOR=10  INTERVAL=1  FRAMES=0(forever)   Ctrl-C to quit.
set -uo pipefail

API="${API:-http://localhost:8080}"
MAX_FLOOR="${MAX_FLOOR:-10}"
INTERVAL="${INTERVAL:-1}"
FRAMES="${FRAMES:-0}"
COLW=9
FIXED=("$@")
VERSION="2.0"

SELF="$(cd "$(dirname "$0")" && pwd)"
REPO="$SELF/.."

ESC=$'\033'
TTY=0; [ -t 1 ] && TTY=1
if [ "$TTY" = 1 ]; then
  C_MOVE=$'\033[1;32m'; C_STOP=$'\033[1;36m'; C_DIM=$'\033[2;37m'
  C_HEAD=$'\033[1m'; C_SYS=$'\033[1;33m'; C_RST=$'\033[0m'
else
  C_MOVE=""; C_STOP=""; C_DIM=""; C_HEAD=""; C_SYS=""; C_RST=""
fi

hr() { local n="$1" out=''; while (( n-- > 0 )); do out+='─'; done; printf '%s' "$out"; }
center() {
  local s="$1" w="$2" len=${#1}
  (( len >= w )) && { printf '%s' "${s:0:w}"; return; }
  local l=$(( (w-len)/2 )) r; r=$(( w-len-l ))
  printf '%*s%s%*s' "$l" '' "$s" "$r" ''
}
discover() {
  curl -s "$API/api/elevator" 2>/dev/null \
    | grep -oE '"elevatorName":"[^"]*"' | sed -E 's/.*:"([^"]*)"/\1/' | sort -u
}
field() { grep -oE "\"$2\":\"?[^\",}]*\"?" <<<"$1" | head -1 | sed -E 's/.*:"?([^"]*)"?$/\1/'; }

frame=0
while :; do
  frame=$((frame+1))
  # read EVERY frame so the build number tracks new commits live
  BUILD="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo dev)"
  BUILT="$(git -C "$REPO" log -1 --format=%cd --date=format:'%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '--')"

  if [ "${#FIXED[@]}" -gt 0 ]; then names=("${FIXED[@]}"); else mapfile -t names < <(discover); fi
  declare -A FL DR MO
  for n in "${names[@]}"; do
    s="$(curl -s "$API/api/elevator/$n" 2>/dev/null)"
    FL[$n]="$(field "$s" floor)"; DR[$n]="$(field "$s" direction)"; MO[$n]="$(field "$s" motion)"
  done

  # ---- system header (always visible) ----
  bar="$(hr 60)"
  buf="${C_SYS}┌$bar┐${C_RST}"$'\n'
  buf+="${C_SYS}│${C_RST} ${C_HEAD}ELEVATOR SYSTEM${C_RST}  v$VERSION   ${C_SYS}▣ build $BUILD${C_RST}   built $BUILT   frame $frame"$'\n'
  buf+="${C_SYS}└$bar┘${C_RST}"$'\n'
  buf+=" $API   floors ${MAX_FLOOR}..0   (Ctrl-C to quit)"$'\n\n'

  # ---- chart ----
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
    base="    └"; for n in "${names[@]}"; do base+="$(hr "$COLW")┴"; done
    buf+="${base%┴}┘"$'\n'
    hdr="    "; for n in "${names[@]}"; do hdr+=" ${C_HEAD}$(center "$n" "$COLW")${C_RST}"; done
    buf+="$hdr"$'\n\n'
    for n in "${names[@]}"; do
      buf+="   ${C_HEAD}${n}${C_RST}: floor ${FL[$n]:-?}  ${DR[$n]:-?}  ${MO[$n]:-?}"$'\n'
    done
  fi

  if [ "$TTY" = 1 ]; then printf '%s' "${ESC}[H${ESC}[2J${buf}"; else printf '%s\n' "$buf"; fi
  unset FL DR MO; declare -A FL DR MO

  [ "$FRAMES" -gt 0 ] && [ "$frame" -ge "$FRAMES" ] && break
  sleep "$INTERVAL"
done
