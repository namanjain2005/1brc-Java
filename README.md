# 1BRC Java and Python implementations

The Java and Python implementations read `weather_stations.csv` from the repository root.

## Run

Requires Python 3.11+ for the Python scripts and benchmarking, and a JDK with `java` and `javac` on `PATH` for the Java implementations.

```powershell
python test.py
```

To run either Java implementation from the repository root:

```powershell
javac -encoding UTF-8 -d out naive\Main.java CountryStat.java
java -cp out Main

javac -encoding UTF-8 -d out naive2\Main.java naive2\CountryStat.java
java -cp out Main
```

To compare runtime across all three implementations, run `python benchmark.py`. For hotspot profiles, see [PERFORMANCE.md](PERFORMANCE.md); Java profiling requires JDK Flight Recorder support.
