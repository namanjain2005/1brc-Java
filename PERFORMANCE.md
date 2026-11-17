# Performance and correctness

`main` uses the custom byte-key table in `divided_workers/`. The `string-hashmap` branch has the same worker design with String keys and a standard `HashMap`. The `impl/naive`, `impl/naive2`, and `impl/parallel` branches provide the TreeMap, HashMap-plus-sort, and parallel-stream Java baselines. The storage-mode experiments are isolated on `experiment/key-storage`; their results are not measurements of the current `main` implementation.

## Billion-row benchmarks

Benchmarks use the upstream Python generator to create a 1,000,000,000-row file with 8,873 stations on the same 80-processor host (OpenJDK 21). The main comparison dataset is 14.8 GiB. Repository implementations use their in-application timers, which exclude JVM startup. All timings are machine- and dataset-specific.

### Current custom table and String/HashMap

The restored custom byte-key table on `main` and the `string-hashmap` branch were each run three times, sequentially, without warm-up. They produced byte-identical output with SHA-256 `52a367557cbd135afda25013e12dced63d9a8953afd2d27369a99508d83dac71`.

| Implementation | Runs | Average | Median | Range |
| --- | ---: | ---: | ---: | ---: |
| Custom byte-key table (`main`) | 3 | 9.471 s | 9.275 s | 8.945–10.193 s |
| String-key `HashMap` workers | 3 | 12.511 s | 12.533 s | 12.449–12.551 s |

The custom table averaged 3.040 seconds faster in this run. Raw measurements: [`20261117-1b-pair-3runs.csv`](benchmark-results/20261117-1b-pair-3runs.csv).

### Java baseline implementations

These baseline implementations were each run once, without warm-up, on the same dataset. They produced the same exact output checksum as the current custom table. Treat these as reference measurements rather than repeatable medians.

| Java implementation | Runs | Application time | Output check |
| --- | ---: | ---: | --- |
| Parallel streams | 1 | 174.585 s | Exact match |
| HashMap plus sort | 1 | 468.525 s | Exact match |
| TreeMap | 1 | 693.063 s | Exact match |

Raw measurements: [`20261117-1b.csv`](benchmark-results/20261117-1b.csv). That file also includes a one-run String/HashMap measurement and an experimental storage-build result; those are superseded by the repeated current-implementation comparison above.

### Upstream `thomaswue` comparison

The upstream [`CalculateAverage_thomaswue.java`](https://github.com/gunnarmorling/1brc/blob/db064194be375edc02d6dbcd21268ad40f7e2869/src/main/java/dev/morling/onebrc/CalculateAverage_thomaswue.java), at commit `db064194`, was run three times on a separately generated 1B dataset with 8,873 stations. The results were compared after parsing and sorting station statistics; signed zero was normalized because the output formatting differs. All results matched with normalized SHA-256 `6ebbbc2fe0c4679d784eb5f19793275a58d0879d88f699709cc576525654d145`.

| Implementation | Runs | Median wall time | Range | Timing scope |
| --- | ---: | ---: | ---: | --- |
| Custom byte-key table (`main`) | 3 | 10.739 s | 10.567–12.386 s | Application timer |
| String-key `HashMap` workers | 3 | 14.361 s | 14.093–14.989 s | Application timer |
| Upstream `thomaswue` | 3 | 3.439 s | 3.373–3.461 s | Whole process, including JVM startup |

This comparison used a different generated file from the paired benchmark above, so compare rankings within each dataset rather than combining the times. The raw `thomaswue` comparison is recorded in [`20261117-1b.csv`](benchmark-results/20261117-1b.csv).

## Correctness

The current custom byte-key table and String/HashMap worker implementations produced byte-identical results on the paired billion-row dataset. The Java baselines in the table above also matched those output bytes. In the separate `thomaswue` comparison, station statistics matched after signed-zero normalization.
