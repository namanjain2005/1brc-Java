# 1BRC Java and Python implementations

The Java and Python implementations read `weather_stations.csv` from the repository root.
Java implementations use the `naive`, `naive2`, and `parallel` packages. Shared Java classes live in `util`.

## Run

Requires Python 3.11+ for the Python scripts and benchmarking, and a JDK with `java` and `javac` on `PATH` for the Java implementations.

```powershell
python test.py
```

To run a Java implementation from the repository root:

```powershell
javac -encoding UTF-8 -d out util\CountryStat.java util\Temperature.java naive\Main.java
java -cp out naive.Main

javac -encoding UTF-8 -d out util\CountryStat.java util\Temperature.java naive2\Main.java
java -cp out naive2.Main

javac -encoding UTF-8 -d out util\Temperature.java parallel\Main.java
java -cp out parallel.Main
```

To compare runtime across the Java variants and Python, run `python benchmark.py`. For hotspot profiles, see [PERFORMANCE.md](PERFORMANCE.md); Java profiling requires JDK Flight Recorder support.
