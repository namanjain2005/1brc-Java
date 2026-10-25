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

## Correctness

Both Java implementations produced 41,343 rows and matched `expected.txt` in sorted order and values. The check decodes the expected file as UTF-16 and repairs mojibake in station names before comparing.
