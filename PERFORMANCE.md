# Performance and correctness

`main` holds the latest implementation (`divided_workers/`); the `naive/`, `naive2/`, `parallel/`, and Python baselines live on the `impl/*` branches and are benchmarked from fresh clones by `compare.sh`. See the README for the workflow.

All implementations parse the input decimal exactly into units of 1/10,000 degree and aggregate with integer arithmetic. The final ratios use the expected file's binary64 rounding behavior at one decimal place. Java performs that conversion and rounding with integer bit operations; Python formats the final ratio after its integer aggregation. `naive/` uses a `TreeMap`, `naive2/` uses a `HashMap` and a final sort, `parallel/` uses `BufferedReader.lines().parallel()` with `groupingBy` and `summarizingLong`, then sorts the result in a `TreeMap`, and `divided_workers/` splits the file into byte-range chunks read by a fixed thread pool, aggregates per chunk, and merges the per-chunk maps.

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

### Linux comparison including divided workers

A later `python benchmark.py` run on 2026-11-13 (same machine, OpenJDK 21, one warm-up and 11 measured runs) added the chunked `divided_workers/` implementation, which splits the file into byte ranges across a fixed thread pool, aggregates per chunk, and merges the per-chunk maps.

| Implementation | Median timed section |
| --- | ---: |
| Java TreeMap (`naive/`) | 0.709 s |
| Java HashMap plus sort (`naive2/`) | 0.745 s |
| Java parallel streams (`parallel/`) | 0.505 s |
| Java divided workers (`divided_workers/`) | 0.768 s |
| Python (`test.py`) | 0.255 s |

On this small input (44,691 lines, ~0.8 MB) the chunked implementation was slower than every sequential Java variant: the thread pool, per-worker `RandomAccessFile` buffers, and the merge step cost more than parallel parsing saves, and the whole file already sits in the page cache so there is no I/O overlap to win. The chunked design targets large inputs where the file does not fit in cache and parsing dominates; on this sample the parallel-stream implementation remains the fastest Java option and Python the fastest overall. Treat the cross-run differences between this table and the 2026-10-26 one as run-to-run variance.

### Billion-row comparison

`generator/` produced a 1BRC-style dataset of exactly 1,000,000,000 rows (14.7 GiB) from the 44,691 station names in `weather_stations.csv` with uniform temperatures in [-99.9, 99.9] at one decimal place. The dataset lives outside the repository (it is git-ignored); see the README for the generator command. All five implementations were run on the same machine (OpenJDK 21 for Java, Python 3.13.5, 80 cores, 503 GiB RAM, dataset in the page cache after the first pass). `divided_workers/` and `parallel/` were run 3–4 times each; the others once. Every implementation produced byte-identical output with 41,343 station rows.

| Implementation | Time on 1,000,000,000 rows |
| --- | ---: |
| Java divided workers (`divided_workers/`, 80 threads) | 21.9 – 23.1 s |
| Java parallel streams (`parallel/`) | 178 – 192 s |
| Java HashMap plus sort (`naive2/`) | 483 s (~8 min) |
| Java TreeMap (`naive/`) | 834 s (~14 min) |
| Python (`test.py`) | 2845 s (~47 min) |

At this scale the chunked implementation was about 8 times faster than parallel streams, 22 times faster than the HashMap variant, 38 times faster than the TreeMap variant, and 130 times faster than Python — the reverse of the ranking on the 44,691-row sample, where Python and the sequential Java variants won. The reason is that on a billion rows the per-row parse and aggregate work dominates and parallelizing it across cores pays off, while on the tiny sample process coordination, output formatting of 41k rows, and cache-resident I/O dominate instead.

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

`divided_workers/` was also validated byte for byte against `expected.txt` after its chunk-boundary handling (peeking at the byte before a chunk start so a valid line is never skipped), exact `Temperature.parseScaled` parsing, and UTF-8 line decoding (replacing the ISO-8859-1 decoding of `RandomAccessFile.readLine()`) were fixed.

On the generated billion-row dataset, all four Java implementations and Python agreed byte for byte with each other (41,343 station rows), and on a 10,000,000-row slice from the same generator all four Java implementations agreed byte for byte as well.
