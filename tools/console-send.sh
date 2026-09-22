#!/bin/bash
# Run one command on the production server console.
#
# The server's console channel is dead - the process was orphaned by its MCSM pty helper, so neither
# the panel nor the daemon can type into it. The JVM itself is still reachable, so this loads a tiny
# agent with the Attach API and dispatches the command on the server thread with operator permission.
#
# The command's output goes to the server log, not to stdout, so this prints the log lines it caused.
#
# Usage: tools/console-send.sh 'mcagent status'
#        tools/console-send.sh 'mcagent spawn Agent'
set -euo pipefail

SERVER="/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
DAEMON="minecraftserver-daemon-1"
TOOLS="$SERVER/mcagent-runtime/tools"
LOG="$SERVER/logs/latest.log"
CMD="${1:?usage: console-send.sh '<command without leading slash>'}"

PID=$(docker exec "$DAEMON" sh -lc "for p in /proc/[0-9]*; do c=\$(tr '\0' ' ' < \$p/cmdline 2>/dev/null); case \"\$c\" in *21.1.248*) echo \${p#/proc/}; break;; esac; done")
if [ -z "$PID" ]; then echo "!! production server process not found" >&2; exit 1; fi

BEFORE=$(wc -l < "$LOG")
docker exec "$DAEMON" sh -lc "cd '$TOOLS' && /j25/bin/java -cp . Attach $PID '$TOOLS/console-agent.jar' '$CMD' 2>&1 | tail -3"
sleep 2
echo "--- log (pid $PID, +$(( $(wc -l < "$LOG") - BEFORE )) lines) ---"
tail -n +"$((BEFORE + 1))" "$LOG" | grep -vE "Attach|agent loaded" | cut -c1-220
