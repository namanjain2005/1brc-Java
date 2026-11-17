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

### Billion-row benchmark: upstream-generated dataset

The upstream repository's non-authoritative Python generator, [`create_measurements.py`](https://github.com/gunnarmorling/1brc/blob/main/src/main/python/create_measurements.py), generated exactly 1,000,000,000 rows from its `data/weather_stations.csv`. The generated file is 14.9 GiB at `/tmp/1brc-upstream/data/measurements.txt` and contains 8,878 distinct stations. The [upstream README](https://github.com/gunnarmorling/1brc#running-the-challenge) identifies the Java generator as authoritative.

Each implementation was run once, with no warm-up, against this same file using `compare.sh`. Java used OpenJDK 21 with UTF-8 output; Python used Python 3.13.5. Runs were sequential on the same 80-core host with 503 GiB RAM. Every implementation produced 8,878 output rows with the same SHA-256 checksum, `b74cdf76174eb558358b766cb28c35c677058c77c8668e82bf93e5ff13639a4e`.

| Implementation | Single run | Output check |
| --- | ---: | --- |
| Java custom byte-key table (`main`, 80 workers) | 11.486 s | Match |
| Java String-key `HashMap` workers (`string-hashmap`, 80 workers) | 13.345 s | Match |
| Java parallel streams (`parallel/`) | 189.268 s | Match |
| Java `HashMap` plus sort (`naive2/`) | 446.298 s | Match |
| Java TreeMap (`naive/`) | 698.465 s | Match |
| Python (`test.py`) | 2596.935 s | Match |

On this single run, the custom byte-key version was about 16 times faster than parallel streams, 39 times faster than HashMap plus sort, 61 times faster than TreeMap, and 226 times faster than Python. These are single-pass measurements, not medians; the values are specific to this generated file and host. The reduced station count also makes the smaller initial custom table especially effective. The SIMD scanner and zero-copy line views continue to avoid per-row byte-array copies.

### Smaller table and sampled-byte hash

The upstream-generated file has 8,878 distinct stations, so a 2,000- or 4,000-entry cap would overflow. The custom table now starts with 16,384 buckets (12,288 entries at a 75% load factor), grows by doubling when needed, and keeps its 100,000-entry maximum. Its hash mixes the name length with up to the first and last four bytes instead of hashing every byte.

To measure these combined changes, the prior implementation (131,072 initial buckets and full-byte hashing) and the tuned implementation were each run five times with no warm-up on both available 1B datasets. Results are medians; ranges show all five runs.

| Dataset | Prior median (range) | Tuned median (range) | Change | Correctness |
| --- | ---: | ---: | ---: | --- |
| Upstream Python generator, 8,878 stations | 14.753 s (13.251–15.189) | 11.686 s (11.365–12.854) | 3.067 s faster | All outputs match `b74cdf76…13639a4e` |
| Earlier generator, 41,343 stations | 20.019 s (17.961–21.053) | 18.066 s (17.265–19.582) | 1.953 s faster | All outputs match `5cce5b17…252896f` |

The tuned table resized on the higher-cardinality dataset and still matched its reference output. The improvement exceeded 3 seconds on the upstream-generated file; timings are specific to these datasets and this host.

At this scale the chunked implementation is about 10 times faster than parallel streams, 27 times faster than the HashMap variant, 47 times faster than the TreeMap variant, and 160 times faster than Python — the reverse of the ranking on the 44,691-row sample, where Python and the sequential Java variants won. The reason is that on a billion rows the per-row parse and aggregate work dominates and parallelizing it across cores pays off, while on the tiny sample process coordination, output formatting of 41k rows, and cache-resident I/O dominate instead.

### Upstream `thomaswue` comparison

On 2026-11-17, I cloned the upstream [`CalculateAverage_thomaswue.java`](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_thomaswue.java) at commit `db064194` and compared it with both local worker variants on this 80-processor host. The upstream Python generator created a fresh file with exactly 1,000,000,000 rows and 8,873 distinct output stations. The run took 11m 51s to generate; the 14.8 GiB dataset remains at `/tmp/1brc-thomas-upstream/data/measurements.txt`.

Each implementation ran three measured passes, sequentially in a rotated order, with no warm-up. Times below are whole-process wall time, including JVM startup. The upstream code was compiled and run on OpenJDK 21 with preview and native access enabled. Its brace-delimited output and our row output were parsed into the same sorted station/min/mean/max representation; all 18 runs across both worker-count settings matched, with normalized SHA-256 `6ebbbc2fe0c4679d784eb5f19793275a58d0879d88f699709cc576525654d145`.

| Implementation | Median wall time | Range | Speedup vs. custom byte table |
| --- | ---: | ---: | ---: |
| Java custom byte-key table (`main`, default 80 workers) | 10.739 s | 10.567–12.386 s | 1.00× |
| Java String-key `HashMap` workers (`string-hashmap`, default 80 workers) | 14.361 s | 14.093–14.989 s | 0.75× (slower) |
| Upstream Java `thomaswue` (default 80 workers) | 3.439 s | 3.373–3.461 s | 3.12× |

As a worker-count sensitivity check, all three were also run three times with `-XX:ActiveProcessorCount=4`; medians were 37.797 s (custom byte table), 56.744 s (String/HashMap), and 15.319 s (`thomaswue`). Those capped results are not the host-default comparison. On the default configuration, `thomaswue` was 7.300 s faster than our custom implementation on this dataset. The dataset has a newly randomized station/temperature sample, so these figures are a separate comparison and should not be merged with the prior 8,878-station run's table.

### Current 1B comparison (Python omitted)

On 2026-11-17, the current custom byte-table build, `string-hashmap`, TreeMap, HashMap-plus-sort, and parallel streams were each run once with no warm-up on the 1,000,000,000-row upstream Python-generator file (14.8 GiB, 8,873 output stations). The user asked to omit Python from this rerun. Repository implementations use the application timer, excluding JVM startup. Upstream `thomaswue` was run three times and is timed by whole-process wall clock, including JVM startup.

| Implementation | Runs | Median / observed time | Range | Result check |
| --- | ---: | ---: | ---: | --- |
| Custom byte-key table (`main`, `copy` storage) | 1 | 12.339 s | — | Exact stdout SHA-256 `52a36755…83dac71` |
| String-key `HashMap` workers | 1 | 14.522 s | — | Exact stdout SHA-256 `52a36755…83dac71` |
| Java TreeMap (`naive/`) | 1 | 693.063 s | — | Exact stdout SHA-256 `52a36755…83dac71` |
| Java HashMap plus sort (`naive2/`) | 1 | 468.525 s | — | Exact stdout SHA-256 `52a36755…83dac71` |
| Java parallel streams (`parallel/`) | 1 | 174.585 s | — | Exact stdout SHA-256 `52a36755…83dac71` |
| Upstream `thomaswue` (commit `db064194`) | 3 | 3.731 s | 3.687–3.798 s | Numeric station stats match after signed-zero normalization |

The five repository implementations produced identical output bytes. `thomaswue` formats the result as a brace-delimited map and prints `0.0` for some averages where our formatter prints `-0.0`; after parsing min/mean/max and normalizing signed zero, all 8,873 station results matched (normalized SHA-256 `6ebbbc2fe0c4679d784eb5f19793275a58d0879d88f699709cc576525654d145`). It ran with OpenJDK 21 preview and native access enabled. These are single-pass timings except for `thomaswue`; host load and run-to-run variation are material, especially in the 12.339 s custom-table result. The raw CSV is in `benchmark-results/20261117-1b.csv`.

A separate ten-run-per-version comparison of the original custom parser and the one-decimal candidate showed overlapping ranges: original median 9.600 s (8.905–10.796 s), one-decimal median 9.768 s (9.035–10.374 s). The specialized parser was not kept in `main` because it showed no repeatable gain and removed malformed-input validation.

### Stable-key storage experiments

The worker table can now be selected with `-Dkey.storage=copy|arena|mapped`; `copy` remains the default. `copy` preserves the existing per-key byte arrays. `arena` stores bytes contiguously in a per-worker growable array and keeps offsets in buckets. `mapped` stores absolute file offsets and compares against read-only 1 GiB memory-mapped windows. All three modes were run five times, in a rotated order, on the same 1B-row/8,873-station dataset. Every output was byte-identical (`52a36755…83dac71`). The application timer includes the file mapping setup for `mapped`.

| Key storage | Median timed section | Range |
| --- | ---: | ---: |
| Per-key byte arrays (`copy`) | 9.410 s | 7.848–9.815 s |
| Per-worker key arena (`arena`) | 83.243 s | 8.477–125.154 s |
| Mapped-file offsets (`mapped`) | 10.343 s | 10.114–19.223 s |

The arena timings are too unstable to support a speed claim, despite correct output; a separate JFR run completed in 8.311 s. The mapped mode is slower than the copy table on its typical warm runs and has slow outliers, consistent with scattered key reads and page-fault costs. Neither mode replaces the copy default. Memory usage was not measured. These implementations are exploratory and should be profiled further before adoption.

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

On the earlier generated billion-row dataset, all four Java implementations and Python agreed byte for byte with each other (41,343 station rows), and on a 10,000,000-row slice from that generator all four Java implementations agreed byte for byte as well. On the upstream-generated billion-row dataset, all six implementations in the comparison table agreed byte for byte (8,878 station rows).
