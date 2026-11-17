# 1BRC Java benchmark

This repository contains a Java implementation of the One Billion Row Challenge and a small harness for comparing it with the other implementations in this project.

The current `main` implementation is in `divided_workers/`. It splits the input into byte ranges, parses each range on a worker thread, aggregates station data in a custom byte-key hash table, and merges the worker tables. It uses fixed-point integer arithmetic for temperatures and tries the JDK Vector API for byte scanning, with a scalar fallback.

## Requirements

- JDK 21 or newer
- Python 3 for the benchmark scripts
- Git, to compare the implementation branches

## Build and run

Run these commands from the repository root. By default, the program reads `weather_stations.csv` and writes station rows to standard output; elapsed time is written to standard error.

```bash
mkdir -p out
javac --add-modules jdk.incubator.vector -encoding UTF-8 -d out \
  util/CountryStat.java util/Temperature.java \
  divided_workers/Main.java divided_workers/Worker.java divided_workers/ByteStationTable.java
java --add-modules jdk.incubator.vector -cp out divided_workers.Main
```

Pass another measurements file as the first argument:

```bash
java --add-modules jdk.incubator.vector -cp out divided_workers.Main /path/to/measurements.txt
```

The Vector API module flag is required to compile the scanner and enables SIMD scanning at runtime. If the module is unavailable at runtime, the scanner falls back to scalar byte scanning.

## Generate a large dataset

`generator/` creates rows from the station names in `weather_stations.csv`, with temperatures in the range `-99.9` to `99.9`. A billion rows take about 15 GiB of disk space.

```bash
javac -encoding UTF-8 -d out generator/Main.java
java -cp out generator.Main /path/to/measurements.txt 1000000000 16
```

The generator reads `weather_stations.csv` relative to the current working directory, so run it from the repository root. Its third argument is the number of generator threads.

## Benchmark

Benchmark the current tree with a warm-up and 3 measured runs:

```bash
python3 benchmark.py --runs 3 --warmups 1 /path/to/measurements.txt
```

The default is 11 measured runs and one warm-up. The script compiles each implementation it finds, runs them against the same input, and reports median application time and an output checksum. A matching checksum confirms identical output bytes for that input.

Compare `main` with the available implementation branches:

```bash
RUNS=3 WARMUPS=1 ./compare.sh /path/to/measurements.txt
```

`compare.sh` also benchmarks `string-hashmap` and the `impl/*` branches by cloning each branch into a temporary directory. It saves a timestamped CSV under the ignored `results/` directory. Use `IMPLS="impl/naive impl/naive2"` to select a subset of baseline branches.

## Branches

- `main`: custom byte-key table and chunked workers
- `string-hashmap`: chunked workers using String keys and Java `HashMap`
- `impl/naive`: TreeMap baseline
- `impl/naive2`: HashMap with a final sort
- `impl/parallel`: parallel-stream baseline
- `impl/python`: Python baseline
- `experiment/key-storage`: exploratory key-storage variants

The billion-row benchmark results and their methodology are in [PERFORMANCE.md](PERFORMANCE.md).
