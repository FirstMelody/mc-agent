#!/bin/bash
# Watch the production log for mining-recovery decisions and report the ones that matter.
#
# The question this answers: does the mining-recovery decision ever clear its confidence floor (0.6)
# and actually act, or is it always abstaining? Prints a line per new sample, flags any answer above
# the floor that was dropped, and exits when one is applied (or after the timeout).
#
# Usage: tools/watch-mining-recovery.sh [seconds]      (default 3600)
set -uo pipefail
SERVER="/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
LOG="$SERVER/logs/latest.log"
DEADLINE=$(( $(date +%s) + ${1:-3600} ))
SEEN=$(mktemp)
grep -c "event=MINING_RECOVERY" "$LOG" > /dev/null 2>&1 || true
# Start from what is already there, so only new lines are reported.
grep "event=MINING_RECOVERY" "$LOG" | tail -200 > "$SEEN"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    sleep 20
    NEW=$(grep "event=MINING_RECOVERY" "$LOG" | grep -vFf "$SEEN" 2>/dev/null)
    [ -z "$NEW" ] && continue
    grep "event=MINING_RECOVERY" "$LOG" | tail -200 > "$SEEN"
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        TS=$(echo "$line" | grep -oE "^\[[0-9]+Sep[0-9]+ [0-9:]+" | tr -d '[')
        CHOICE=$(echo "$line" | grep -oE "choice=[A-Z_]+" | cut -d= -f2)
        CONF=$(echo "$line" | grep -oE "confidence=[0-9.]+" | cut -d= -f2)
        if echo "$line" | grep -q "applied=true"; then
            RESULT=$(echo "$line" | grep -oE "result=.*" | cut -c1-90)
            echo "$TS  APPLIED  $CHOICE $CONF  $RESULT"
            rm -f "$SEEN"
            exit 0
        elif echo "$line" | grep -q "not_applied"; then
            WHY=$(echo "$line" | grep -oE "mineJob=[a-z]+ combat=[a-z]+ moving=[a-z]+ queue=[0-9]+ thinking=[a-z]+")
            FLAG=""
            awk -v c="${CONF:-0}" 'BEGIN{exit !(c>=0.6)}' && FLAG="  <-- above the floor, dropped"
            echo "$TS  dropped  $CHOICE ${CONF:-?}  $WHY$FLAG"
        elif [ -n "${CHOICE:-}" ]; then
            FLAG=""
            awk -v c="${CONF:-0}" 'BEGIN{exit !(c>=0.6)}' && FLAG="  (above the floor)"
            echo "$TS  answer   $CHOICE ${CONF:-?}$FLAG"
        fi
    done <<< "$NEW"
done
echo "watch window ended with no applied recovery"
rm -f "$SEEN"
