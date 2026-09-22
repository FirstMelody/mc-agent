#!/bin/bash
# Watch production for the fix that stopped the tunnel macro from killing itself.
#
# The bug: a queued tunnel step named a block the bot had already removed as access clearance, the
# step returned `failed: there is no block at ...`, and every macro step aborts the plan on failure -
# so a 12-block tunnel died after two blocks, every 40 seconds, each death costing a planning turn.
# The fix logs `treats X, Y, Z as already done` when that step is recognised as a no-op.
#
# This prints each occurrence as it happens, and every interval a line of counters, so a quiet window
# and a fixed window look different.
#
# Usage: tools/watch-selfclear.sh [seconds] [interval]      (default 3600, 300)
set -uo pipefail
SERVER="/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
LOG="$SERVER/logs/latest.log"
DEADLINE=$(( $(date +%s) + ${1:-3600} ))
INTERVAL=${2:-300}
SEEN=$(mktemp)
grep "treats .* as already done" "$LOG" | tail -50 > "$SEEN" 2>/dev/null || true

count() { grep -c "$1" "$LOG" 2>/dev/null || echo 0; }

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    sleep 20
    NEW=$(grep "treats .* as already done" "$LOG" | grep -vFf "$SEEN" 2>/dev/null)
    if [ -n "$NEW" ]; then
        grep "treats .* as already done" "$LOG" | tail -50 > "$SEEN"
        while IFS= read -r line; do
            [ -z "$line" ] && continue
            echo "$(date '+%H:%M:%S')  SELF-CLEARED NO-OP  $(echo "$line" | grep -oE 'Bot [A-Za-z]+ treats .*' | cut -c1-120)"
        done <<< "$NEW"
    fi
    if [ $(( $(date +%s) % INTERVAL )) -lt 20 ]; then
        echo "$(date '+%H:%M:%S')  counters: tunnels=$(count 'planned local tunnel')" \
             "planAborts=$(count 'plan stopped at step')" \
             "noBlockAt=$(count 'there is no block at')" \
             "selfClearedNoOps=$(count 'treats .* as already done')" \
             "turns=$(count 'turn usage:')"
        sleep 20
    fi
done
rm -f "$SEEN"
