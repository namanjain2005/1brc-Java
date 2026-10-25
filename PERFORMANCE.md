# Performance and correctness

Measured with `weather_stations.csv` on Windows using OpenJDK 25.0.2 and Python 3.11. The Java implementation is in `naive/`; the Python baseline is `test.py`.

## Timing

Each timing is a full process run, including process startup, reading the input, aggregating measurements, formatting and rounding results, and producing output. Standard output was redirected to the null device. I ran one warm-up followed by seven measured runs, alternating between Java and Python.

| Implementation | Median wall time |
| --- | ---: |
| Java (`double`, sorted output) | 0.588 s |
| Python (`test.py`) | 0.188 s |

This run makes the Java process about 3.1 times slower end to end. It includes JVM startup and is a measurement of these implementations on this machine, not a general language performance comparison.

Commands used:

```powershell
javac -encoding UTF-8 -d naive naive\Main.java CountryStat.java
java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" -cp naive Main
python test.py
```

## Correctness

The Java output was compared line by line with `expected.txt` after decoding its UTF-16 text and repairing the mojibake in station names. All 41,343 rows matched, including their sorted order and formatted numeric values.
