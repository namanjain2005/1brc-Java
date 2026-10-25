# Performance and correctness

`naive/` is the current `TreeMap` implementation. `naive2/` keeps the `HashMap` implementation with a final sort so both approaches can be compared.

## Timing

`test.py` starts its timer after imports and stdout setup, before allocating its aggregation map and reading the file. It stops after printing the station results. Java uses the same scope: each `Main` starts `System.nanoTime()` at the start of `main` and reports elapsed time to stderr after printing the results. Process startup and compilation are excluded from both measurements.

Measured with OpenJDK 25.0.2 and Python 3.11. `python benchmark.py` compiles before timing, runs one warm-up and then 11 samples per implementation, and redirects result rows to the null device. The Python baseline is run from the same `test.py` source; only its final timing line is sent to stderr so the harness can collect it.

| Implementation | Median timed section |
| --- | ---: |
| Java `TreeMap` (`naive/`) | 0.676 s |
| Java `HashMap` plus sort (`naive2/`) | 0.628 s |
| Python (`test.py`) | 0.200 s |

In this run, HashMap plus sorting was about 7% faster than TreeMap, while the two Java medians were close relative to the run-to-run variation. Python was about 3.1 times faster than the HashMap version. TreeMap keeps station names sorted during aggregation; HashMap pays for sorting once before output.

## Correctness

Both Java implementations matched all 41,343 expected rows in sorted order and numeric values. The comparison decodes `expected.txt` as UTF-16 and repairs mojibake in station names before comparing. Each implementation writes its timing line to stderr, leaving result rows on stdout.
