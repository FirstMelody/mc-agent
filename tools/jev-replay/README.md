# jev-replay

Offline calibration for the JEV decision layer.

Shadow mode logs the exact state it sent (`state=…`, one line, truncated) next to the answer it got.
This tool reads those states back, asks the real model again, and reports the choice distribution and
how many cases would be acted on at a given confidence threshold. That is how the thresholds in
`AgentBrain` were chosen, and how a change to the state or the prompt is judged against the same
sample set instead of by taste.

```sh
# 1. collect cases from a running server's log
./extract.sh "/serverstorage/minecraftserver/<server>/logs/latest.log" > cases.tsv

# 2. re-score them (key is passed in, never stored here)
GSON=.../gson-2.10.jar; SLF=.../slf4j-api-1.7.30.jar; ANN=.../annotations-23.0.0.jar
javac -cp "../../build/classes/java/main:$GSON:$SLF:$ANN" -d out Replay.java
java -Dkey=... -Dthreshold=0.6 -cp "out:../../build/classes/java/main:$GSON:$SLF:$ANN" Replay cases.tsv
```

`Replay.java` imports the mod's own `JevClient`, so it exercises the real request shape (including the
systemone envelope and the breaker) rather than a copy of it.
