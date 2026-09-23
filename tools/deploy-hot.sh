#!/bin/bash
# Hot-deploy the runtime to the production server and bring the bots back.
#
# The production console channel is dead: the server processes were orphaned by their MCSM pty
# helper (ppid=1, stdin socket with no peer), so neither the panel nor the daemon can send a command.
# The one channel that always exists is the JVM itself, so this loads a tiny agent with the Attach API
# and runs `mcagent reload` / `mcagent spawn <name>` on the server thread with operator permission.
#
# Usage: tools/deploy-hot.sh [bot-name ...]      (default: Agent)
#        tools/deploy-hot.sh --no-build Agent    (skip build + smoke deploy)
set -euo pipefail

SERVER="/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
DAEMON="minecraftserver-daemon-1"
TOOLS="$SERVER/mcagent-runtime/tools"
JAR="$(cd "$(dirname "$0")/.." && pwd)/build/libs/mcagent-runtime.jar"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/ymtc/Repos/.gradle-home}"
GRADLE=/root/.gradle/wrapper/dists/gradle-8.12-bin/cetblhg4pflnnks72fxwobvgv/gradle-8.12/bin/gradle

BUILD=1
if [ "${1:-}" = "--no-build" ]; then BUILD=0; shift; fi
BOTS=("$@")
[ ${#BOTS[@]} -eq 0 ] && BOTS=(Agent)

if [ "$BUILD" = 1 ]; then
    echo "== build + deploy to the dev server"
    GRADLE_USER_HOME="$GRADLE_USER_HOME" "$GRADLE" build deploySmokeServer --offline | tail -3
fi

# The production server is whichever java process runs the 1.21.1 (21.1.248) args file.
PID=$(docker exec "$DAEMON" sh -lc "for p in /proc/[0-9]*; do c=\$(tr '\0' ' ' < \$p/cmdline 2>/dev/null); case \"\$c\" in *21.1.248*) echo \${p#/proc/}; break;; esac; done")
if [ -z "$PID" ]; then echo "!! could not find the production server process" >&2; exit 1; fi
echo "== production pid $PID"

TS=$(date +%Y%m%d-%H%M%S)
cp "$SERVER/mcagent-runtime/mcagent-runtime.jar" "$SERVER/mcagent-runtime/mcagent-runtime.jar.bak.$TS"
# Replace by rename, never by overwrite. `cp` onto the live jar rewrites the same inode, and the
# running generation still holds that jar open: its class loader then reads a file whose central
# directory has moved out from under it and every class it has not loaded yet dies with
# "ZipException: ZipFile invalid LOC header" / NoClassDefFoundError. A rename leaves the old inode
# untouched for the old generation and gives the new one a complete file.
cp "$JAR" "$SERVER/mcagent-runtime/mcagent-runtime.jar.new"
mv -f "$SERVER/mcagent-runtime/mcagent-runtime.jar.new" "$SERVER/mcagent-runtime/mcagent-runtime.jar"
LOCAL=$(sha256sum "$JAR" | cut -c1-16)
echo "== deployed $LOCAL (backup .bak.$TS)"

send() { docker exec "$DAEMON" sh -lc "cd '$TOOLS' && /j25/bin/java -cp . Attach $PID '$TOOLS/console-agent.jar' '$1' 2>&1 | tail -1"; }

BEFORE_LOAD=$(wc -l < "$SERVER/logs/mcagent.log")
send "mcagent reload"
sleep 8
# `mcagent reload` can be missing from the live dispatcher (the core registers it, so a runtime that
# replaced the whole /mcagent node removes it). The verification below would then read the *previous*
# load line and report success for a reload that never happened, so compare it explicitly.
LOADED=$(tail -n +"$((BEFORE_LOAD + 1))" "$SERVER/logs/mcagent.log" \
    | grep -E "MC Agent runtime loaded from" | tail -1 \
    | grep -o "sha256=[0-9a-f]\{16\}" || true)
if [ "$LOADED" != "sha256=$LOCAL" ]; then
    echo "!! the reload did not take effect: the loaded jar reports ${LOADED:-nothing}, not $LOCAL" >&2
    echo "   If /mcagent reload is missing from the dispatcher, run tools/restore-core-commands.sh" >&2
fi
for bot in "${BOTS[@]}"; do send "mcagent spawn $bot"; sleep 3; done
sleep 3

echo "== verification (server log)"
grep -E "MC Agent runtime loaded from" "$SERVER/logs/mcagent.log" | tail -1 | grep -o "sha256=[0-9a-f]\{16\}" || true
grep -E "Jev settings applied" "$SERVER/logs/mcagent.log" | tail -1 | sed 's/.*Jev settings applied: //' | cut -c1-140 || true
grep -E "Spawned bot" "$SERVER/logs/latest.log" | tail -1 | cut -c60-160 || true
if [ "$LOADED" != "sha256=$LOCAL" ]; then exit 1; fi
