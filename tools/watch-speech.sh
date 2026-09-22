#!/bin/bash
# Watch production for what the bot decides to say, and why it did not say it.
#
# Three lines matter:
#   JEV ... SPEECH_GATE applied=stay_silent ...      the gate decided, before any model turn
#   not sent: this is routine progress narration     a narration line the model wanted to send
#   Bot ... said: ...                                a line that actually went out
#
# Reads by line number, not by matching content. The first version re-grepped the whole log every pass
# and filtered with `grep -vFf` against a `tail -100` snapshot; once the log held more than a hundred
# matching lines, everything older than the newest hundred was reported as "new" on every single pass,
# so the watcher printed the morning's history over and over and hid the lines it existed for.
#
# Usage: tools/watch-speech.sh [seconds]
set -uo pipefail
SERVER="/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server"
LOG="$SERVER/logs/latest.log"
DEADLINE=$(( $(date +%s) + ${1:-3600} ))
PATTERN="event=SPEECH_GATE|routine progress narration|Bot Agent.*said"
POS=$(wc -l < "$LOG")

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    sleep 15
    NOW=$(wc -l < "$LOG")
    if [ "$NOW" -le "$POS" ]; then
        POS=$NOW
        continue
    fi
    NEW=$(tail -n +"$((POS + 1))" "$LOG" | grep -E "$PATTERN")
    POS=$NOW
    [ -z "$NEW" ] && continue
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        TS=$(echo "$line" | grep -oE "^\[[0-9]+Sep[0-9]+ [0-9:]+" | tr -d '[' | cut -d' ' -f2)
        if echo "$line" | grep -q "applied=stay_silent"; then
            CONF=$(echo "$line" | grep -oE "confidence=[0-9.]+" | cut -d= -f2)
            echo "$TS  GATE-SILENT       confidence=$CONF  (no model turn, no tokens)"
        elif echo "$line" | grep -q "not_applied"; then
            echo "$TS  GATE-NOT-APPLIED  $(echo "$line" | grep -oE 'not_applied=[a-z_]+')"
        elif echo "$line" | grep -q "routine progress narration"; then
            echo "$TS  NARRATION-REFUSED $(echo "$line" | grep -oE 'called say\(message=\"[^\"]{0,46}')"
        elif echo "$line" | grep -q "said:"; then
            echo "$TS  SENT              $(echo "$line" | grep -oE 'said: .{0,66}')"
        fi
    done <<< "$NEW"
done
