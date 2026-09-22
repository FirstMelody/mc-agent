#!/bin/bash
# Run one dev-server harness, holding a lock so two agents in this checkout never start two servers.
#
# Two `runServer` tasks in the same checkout collide on the world's session.lock and the loser dies
# with "already locked (possibly by other Minecraft instance?)" - which looks like a test failure and
# is not one. The lock is a directory, because mkdir is atomic; a lock older than 15 minutes is
# treated as stale (a crashed run) and reclaimed.
#
# Usage: tools/run-harness.sh MCAGENT_ROUTING_TEST=true [more env assignments]
set -euo pipefail
cd "$(dirname "$0")/.."

LOCK=build/.harness-lock
DEADLINE=$(( $(date +%s) + 900 ))
while true; do
    if mkdir "$LOCK" 2>/dev/null; then
        echo $$ > "$LOCK/pid"
        trap 'rm -rf "$LOCK"' EXIT
        break
    fi
    if [ -f "$LOCK/created" ] && [ $(( $(date +%s) - $(cat "$LOCK/created") )) -gt 900 ]; then
        echo "== reclaiming a stale harness lock"
        rm -rf "$LOCK"
        continue
    fi
    if [ "$(date +%s)" -gt "$DEADLINE" ]; then
        echo "!! another harness has held the lock for 15 minutes; giving up" >&2
        exit 1
    fi
    echo "== waiting for the harness lock ($(cat "$LOCK/pid" 2>/dev/null || echo '?'))"
    sleep 5
done
date +%s > "$LOCK/created"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/ymtc/Repos/.gradle-home}"
GRADLE=/root/.gradle/wrapper/dists/gradle-8.12-bin/cetblhg4pflnnks72fxwobvgv/gradle-8.12/bin/gradle

echo "== build + deploy to the dev server"
"$GRADLE" build deploySmokeServer --offline | tail -2

echo "== run: $*"
env "$@" "$GRADLE" --no-daemon runServer --offline
