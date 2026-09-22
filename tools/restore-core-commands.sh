#!/bin/bash
# Put the core's /mcagent runtime and /mcagent reload back on the live dispatcher.
#
# Recovery tool. Those two subcommands are registered by the core module - they have to survive a
# runtime reload, because `reload` is how a new jar gets loaded at all - but Brigadier merges into the
# live tree, and a runtime generation that removed the whole /mcagent node before re-registering its
# own tree took them with it. Brigadier has no unregister, so the way back is to run the core's own
# registration again; that is all the attached agent does.
#
# The class name is unique per run on purpose: HotSpot memoises the class loader of the *first*
# attached agent jar for the life of the JVM, so re-attaching a jar that defines the same class name
# silently runs the previously loaded bytes instead. A fresh name is what makes a rebuilt agent take
# effect at all.
#
# Usage: tools/restore-core-commands.sh
set -euo pipefail

SERVER="/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
DAEMON="minecraftserver-daemon-1"
TOOLS="$SERVER/mcagent-runtime/tools"
SRC="$(cd "$(dirname "$0")" && pwd)/restore-core-commands/RestoreCoreCommands.java"

NAME="RestoreCoreCommands_$(date +%H%M%S)_$RANDOM"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

sed "s/RestoreCoreCommands/$NAME/g" "$SRC" > "$WORK/$NAME.java"
javac --release 17 -d "$WORK" "$WORK/$NAME.java"
printf 'Agent-Class: %s\nCan-Retransform-Classes: false\n' "$NAME" > "$WORK/MANIFEST.MF"
(cd "$WORK" && jar cfm "$WORK/agent.jar" MANIFEST.MF "$NAME.class")
cp "$WORK/agent.jar" "$TOOLS/$NAME.jar"

PID=$(docker exec "$DAEMON" sh -lc "for p in /proc/[0-9]*; do c=\$(tr '\0' ' ' < \$p/cmdline 2>/dev/null); case \"\$c\" in *21.1.248*) echo \${p#/proc/}; break;; esac; done")
if [ -z "$PID" ]; then echo "!! could not find the production server process" >&2; exit 1; fi
echo "== production pid $PID, agent class $NAME"

docker exec "$DAEMON" sh -lc "cd '$TOOLS' && /j25/bin/java -cp . Attach $PID '$TOOLS/$NAME.jar' restore 2>&1 | tail -3"
sleep 2
grep -E "\[restore-core-commands\]" "$SERVER/logs/latest.log" | tail -4 | cut -c1-200
