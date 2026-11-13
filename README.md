# 1BRC Java and Python implementations

The Java and Python implementations read `weather_stations.csv` from the repository root by default, or the file given as the first program argument. Java implementations use the `naive`, `naive2`, `parallel`, `divided_workers`, and `generator` packages. Shared Java classes live in `util`.

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

javac -encoding UTF-8 -d out util\CountryStat.java util\Temperature.java divided_workers\Main.java divided_workers\Worker.java
java -cp out divided_workers.Main
```

Pass a path as the first argument to run against a different input file:

```powershell
java -cp out divided_workers.Main measurements.txt
```

### Generate a billion-row dataset

`generator/` writes a 1BRC-style measurements file (default temperatures uniform in [-99.9, 99.9], one decimal digit) using the station names from `weather_stations.csv`:

```powershell
javac -encoding UTF-8 -d out generator\Main.java
java -cp out generator.Main measurements.txt 1000000000 16
```

The generated file is about 15 GiB for one billion rows, so point it at a filesystem with enough free space.

To compare runtime across the Java variants and Python, run `python benchmark.py`. For hotspot profiles, see [PERFORMANCE.md](PERFORMANCE.md); Java profiling requires JDK Flight Recorder support.
