# Performance and correctness

All implementations parse the input decimal exactly into units of 1/10,000 degree and aggregate with integer arithmetic. The final ratios use the expected file's binary64 rounding behavior at one decimal place. Java performs that conversion and rounding with integer bit operations; Python formats the final ratio after its integer aggregation. `naive/` uses a `TreeMap`, `naive2/` uses a `HashMap` and a final sort, and `parallel/` uses `BufferedReader.lines().parallel()` with `groupingBy` and `summarizingLong`, then sorts the result in a `TreeMap`.

## Timing

`test.py` starts its timer after imports and stdout setup, before creating its aggregation map and reading the input. It stops after printing station rows. All Java programs time from the start of `main` through result printing. Process startup and compilation are excluded.

Historical double-based timings measured on Windows with OpenJDK 25.0.2 and Python 3.11, before the exact fixed-point conversion. The current implementations use exact fixed-point arithmetic; use the Linux comparison below for current timings. `python benchmark.py` compiles into a temporary directory, performs one warm-up and 11 measured runs per implementation, and sends result rows to the null device.

| Implementation | Median timed section |
| --- | ---: |
| Java TreeMap (`naive/`) | 0.539 s |
| Java HashMap plus sort (`naive2/`) | 0.656 s |
| Python (`test.py`) | 0.232 s |

Run-to-run timing varied, so treat these as approximate. In this run, the Python baseline was about 2.3 times faster than the TreeMap version.

### Linux comparison

After the Java package refactor, `python benchmark.py` was run in this workspace on 2026-10-26 with OpenJDK 21.0.13 and Python 3.13.5. It used one warm-up and 11 measured runs per implementation. The input contains 44,691 measurements and produces 41,343 station rows.

| Implementation | Median timed section |
| --- | ---: |
| Java TreeMap (`naive/`) | 0.620 s |
| Java HashMap plus sort (`naive2/`) | 0.647 s |
| Java parallel streams (`parallel/`) | 0.452 s |
| Python (`test.py`) | 0.216 s |

On this run, the parallel-stream implementation was about 1.4 times faster than the TreeMap version. Python was about 2.1 times faster than the parallel-stream implementation. Timings depend on the machine and input size.

## Hotspots

Run `python profile_hotspots.py` for JFR hot-method tables and stack samples for the two baseline Java versions, plus a `cProfile` table for Python. It also writes two Java flamegraphs and a Python hotspot chart to `profile-output/`; open `profile-output/index.html` to view them together. The profiler warms each Java process once, records 15 runs, and profiles Python's timed body over 15 runs. These are sampled profiles; cProfile adds overhead, so use the call rankings rather than its elapsed time as a benchmark. The profile table below is historical and reflects the pre-conversion double-based implementations; rerun the script for current profiles.

| Implementation | Largest observed hot paths |
| --- | --- |
| Java TreeMap | `TreeMap.fixAfterInsertion`: 37 samples (16%); `TreeMap.getEntry`: 12 (5%); `Main.main`: 75 (33%). Input splitting, double parsing, and output writes also appear in stacks. |
| Java HashMap plus sort | TimSort methods: 69 samples combined (34%); output writes: 30 (15%); `HashMap.getNode`: 11 (5%). |
| Python | Across 15 profiled runs, `print` used 7.146 s (63% of profiled time), the defaultdict initializer 1.415 s (13%), `sorted` 1.302 s (12%), and `str.split` 0.824 s (7%). |

This points to TreeMap lookup/insertion in `naive/`, sorting in `naive2/`, and row printing in Python as the clearest hotspots. JFR stack samples show the Java input path through `String.split`, `Double.parseDouble`, and each map's get/put path.

## Correctness

All three Java implementations and both Python scripts produced 41,343 rows and matched the UTF-8 `expected.txt` byte for byte. The check also covered positive, negative, and exact half-even boundary values.
