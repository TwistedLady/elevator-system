#!/usr/bin/env bash
# Order an elevator to a floor, then monitor until it arrives — with a pass/fail check.
#
# Usage: scripts/demo.sh [elevatorName] [floor]
#   e.g. scripts/demo.sh lift-a 5
set -uo pipefail

API="${API:-http://localhost:8080}"
ELEVATOR="${1:-lift-a}"
FLOOR="${2:-5}"
TIMEOUT="${TIMEOUT:-20}"

# tiny JSON field readers (no jq dependency)
json_num() { grep -oE "\"$2\":[0-9]+" <<<"$1" | grep -oE '[0-9]+' | head -1; }
json_str() { grep -oE "\"$2\":\"[^\"]*\"" <<<"$1" | sed -E 's/.*:"([^"]*)"/\1/' | head -1; }

echo "==> Ordering '$ELEVATOR' to floor $FLOOR"
ORDER_RESP="$(curl -s -X POST "$API/api/order" \
  -H 'Content-Type: application/json' \
  -d "{\"elevatorName\":\"$ELEVATOR\",\"floor\":$FLOOR}")"
echo "    request accepted: $ORDER_RESP"
TAG="$(json_str "$ORDER_RESP" tag)"
echo "    tag: ${TAG:-<none>}"
echo

echo "==> Monitoring $API/api/elevator/$ELEVATOR (timeout ${TIMEOUT}s)"
LAST=""
for i in $(seq 1 "$TIMEOUT"); do
  STATE="$(curl -s "$API/api/elevator/$ELEVATOR" || true)"
  if [ -n "$STATE" ] && [ "$STATE" != "$LAST" ]; then
    CUR="$(json_num "$STATE" floor)"
    MOT="$(json_str "$STATE" motion)"
    DIR="$(json_str "$STATE" direction)"
    printf "    t=%-2s floor=%-3s dir=%-4s motion=%s\n" "$i" "${CUR:-?}" "${DIR:-?}" "${MOT:-?}"
    LAST="$STATE"
    # "Arrived" in this design = the elevator's floor equals the target. (On arrival the
    # Controller clears the request, so it never emits a separate STOPPED state — see THOUGHTS.md.)
    if [ "${CUR:-}" = "$FLOOR" ]; then
      echo
      echo "PASS ✓  '$ELEVATOR' reached floor $FLOOR."
      exit 0
    fi
  fi
  sleep 1
done

echo
echo "FAIL ✗  '$ELEVATOR' did not reach floor $FLOOR within ${TIMEOUT}s."
echo "        last state: ${STATE:-<none>}"
exit 1
