# Performance and correctness

`naive/` is the TreeMap implementation. `naive2/` is the HashMap implementation with a final sort. Both use `double` for aggregation and exact integer arithmetic to round to one decimal place with ties to even.

## Timing

`test.py` starts its timer after imports and stdout setup, before creating its aggregation map and reading the input. It stops after printing station rows. Both Java programs time from the start of `main` through result printing. Process startup and compilation are excluded.

Measured on Windows with OpenJDK 25.0.2 and Python 3.11. `python benchmark.py` compiles into a temporary directory, performs one warm-up and 11 measured runs per implementation, and sends result rows to the null device.

| Implementation | Median timed section |
| --- | ---: |
| Java TreeMap (`naive/`) | 0.539 s |
| Java HashMap plus sort (`naive2/`) | 0.656 s |
| Python (`test.py`) | 0.232 s |

Run-to-run timing varied, so treat these as approximate. In this run, the Python baseline was about 2.3 times faster than the TreeMap version.

## Hotspots

Run `python profile_hotspots.py` for JFR hot-method tables and stack samples for both Java versions, plus a `cProfile` table for Python. It also writes two Java flamegraphs and a Python hotspot chart to `profile-output/`; open `profile-output/index.html` to view them together. The profiler warms each Java process once, records 15 runs, and profiles Python's timed body over 15 runs. These are sampled profiles; cProfile adds overhead, so use the call rankings rather than its elapsed time as a benchmark.

| Implementation | Largest observed hot paths |
| --- | --- |
| Java TreeMap | `TreeMap.fixAfterInsertion`: 37 samples (16%); `TreeMap.getEntry`: 12 (5%); `Main.main`: 75 (33%). Input splitting, double parsing, and output writes also appear in stacks. |
| Java HashMap plus sort | TimSort methods: 69 samples combined (34%); output writes: 30 (15%); `HashMap.getNode`: 11 (5%). |
| Python | Across 15 profiled runs, `print` used 7.146 s (63% of profiled time), the defaultdict initializer 1.415 s (13%), `sorted` 1.302 s (12%), and `str.split` 0.824 s (7%). |

This points to TreeMap lookup/insertion in `naive/`, sorting in `naive2/`, and row printing in Python as the clearest hotspots. JFR stack samples show the Java input path through `String.split`, `Double.parseDouble`, and each map's get/put path.

## Correctness

Both Java implementations produced 41,343 rows and matched `expected.txt` in sorted order and values. The check decodes the expected file as UTF-16 and repairs mojibake in station names before comparing.
