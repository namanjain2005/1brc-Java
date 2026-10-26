# Linux perf profile: `naive`

Profiled the checked-in `weather_stations.csv` (44,691 rows, about 805 KB) after pulling commit `ca7f113`. The machine used Linux x86_64, Temurin JDK 21.0.13, and Linux `perf` 3.10.

## Results

`perf record` ran 20 `Main.main` passes in one JVM and captured 3,207 samples with no lost samples. The leading symbols were:

| Symbol | Samples |
| --- | ---: |
| `Main.main` | 9.25% |
| `G1ParScanThreadState::trim_queue_to_threshold` | 9.23% |
| `StreamEncoder.flushBuffer` | 4.86% |
| `Formatter$FormatSpecifier.printString` | 4.44% |
| `TreeMap.put` | 3.65% |
| `String.split` | 1.45% |

The repeated `perf stat` run averaged 0.76713 seconds wall time per JVM process, including JVM startup. The program's own timer had a 0.667-second median. Average counters per process: 2.28239 seconds task-clock, 5,009,960,483 cycles, 6,070,725,524 instructions (1.21 IPC), 7,529,438 cache misses, and 42,442,516 branch misses.

## Artifacts

- `naive.perf.data`: raw Linux perf recording.
- `perf-55748.map`: HotSpot JIT symbol map for the recording.
- `perf-report.txt`: symbolized `perf report` summary.
- `perf-stat.txt`: five-run counter output and per-run application timings.
- `perf-record.log`: per-pass timings from the recorded run.
- `profiling/PerfRunner.java`: runner used to repeat `naive.Main.main` 20 times.

The JDK writes the JIT map to `/tmp/perf-PID.map`; it was moved into this directory after capture. No perf recording or map remains in `/tmp`.

## Commands

Run from the repository root:

```bash
mkdir -p profile-output/classes
"$HOME/.local/jdk-21/bin/javac" -encoding UTF-8 -d profile-output/classes \
  naive/Main.java util/CountryStat.java util/Temperature.java profiling/PerfRunner.java
perf record -F 199 --call-graph fp -o profile-output/naive.perf.data -- \
  "$HOME/.local/jdk-21/bin/java" \
  -XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit \
  -XX:+PreserveFramePointer -XX:+ShowHiddenFrames \
  -Dfile.encoding=UTF-8 -cp profile-output/classes profiling.PerfRunner \
  > /dev/null 2> profile-output/perf-record.log
PATH="$HOME/.local/jdk-21/bin:$PATH" perf stat -r 5 \
  -e task-clock,cycles,instructions,cache-misses,branch-misses \
  java -Dfile.encoding=UTF-8 -cp profile-output/classes naive.Main \
  > /dev/null 2> profile-output/perf-stat.txt
```
