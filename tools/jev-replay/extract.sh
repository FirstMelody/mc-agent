#!/bin/sh
# Pull the states shadow mode recorded out of a server log, one case per line: <event><TAB><state>.
# Usage: ./extract.sh <latest.log> > cases.tsv
set -e
grep -o 'JEV [A-Z]* bot=[^ ]* event=[A-Z_]*.*' "$1" \
  | sed -E 's/JEV [A-Z]* bot=[^ ]* event=([A-Z_]+).* state=(.*)$/\1\t\2/' \
  | grep -P '\t' || true
