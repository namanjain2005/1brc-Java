# 1BRC Java and Python implementations

`main` holds the latest implementation (`divided_workers/`), the dataset `generator/`, the shared Java `util/` classes, and the comparison harness. The comparable baselines live on branches: `impl/naive` (TreeMap), `impl/naive2` (HashMap plus sort), `impl/parallel` (parallel streams), and `impl/python` (Python). Each branch contains only that implementation, the shared code it needs, and the sample dataset. This host ships git 1.8, which has no `git worktree`, so the harness compares via fresh local clones.

The implementations read `weather_stations.csv` from the repository root by default, or the file given as the first program argument.

## Run

Requires Python 3.11+ for the benchmarking harness, and a JDK with `java` and `javac` on `PATH` for the Java implementations.

The latest implementation runs from the repository root:

```powershell
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

### Compare against the baselines

`compare.sh` benchmarks the latest implementation and every `impl/*` branch on the same dataset and appends medians plus output checksums to `results/<timestamp>.csv` (git-ignored). Checksums that differ between rows mean an implementation produced different output.

```bash
./compare.sh                              # sample dataset, 5 runs each
./compare.sh /path/to/measurements.txt    # any dataset
RUNS=11 ./compare.sh measurements_10m.txt # more runs for a stable median
```

To work on a baseline, clone its branch: `git clone . ../1brc-naive -b impl/naive` (or push from main after editing). `python benchmark.py [--runs N] [--tree PATH] [dataset]` benchmarks a single tree on its own.
