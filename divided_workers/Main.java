package divided_workers;

import util.CountryStat;
import util.Temperature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Main {
    public static final int noOfThreads = Runtime.getRuntime().availableProcessors();
    public static final String defaultFileName = "weather_stations.csv";

    public static void main(String[] args) {
        long startTime = System.nanoTime();
        String fileName = args.length > 0 ? args[0] : defaultFileName;
        long fileSize = 0;
        try {
            fileSize = Files.size(Path.of(fileName));
        } catch (Exception e) {
            System.err.println("Could not read input file: " + fileName);
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(noOfThreads);
        long chunkSize = fileSize / noOfThreads;
        List<Future<ByteStationTable>> futures = new ArrayList<>();

        for (int i = 0; i < noOfThreads; i++) {
            long start = i * chunkSize;
            long end = (i == noOfThreads - 1) ? fileSize : start + chunkSize;
            futures.add(pool.submit(new Worker(fileName, start, end)));
        }

        ByteStationTable aggregate = ByteStationTable.createDefault();
        try {
            for (Future<ByteStationTable> future : futures) {
                aggregate.merge(future.get());
            }
        } catch (Exception e) {
            System.err.println("Aggregation failed: " + e);
            pool.shutdownNow();
            return;
        } finally {
            pool.shutdown();
        }

        List<OutputRow> rows = new ArrayList<>(aggregate.size());
        for (int i = 0; i < (1 << 17); i++) {
            byte[] key = aggregate.keyAt(i);
            if (key != null) rows.add(new OutputRow(aggregate.nameAt(i), aggregate.valueAt(i)));
        }
        rows.sort((a, b) -> a.name.compareTo(b.name));
        for (OutputRow row : rows) {
            String name = row.name;
            CountryStat stat = row.stat;
            System.out.printf(
                "%s;%s;%s;%s%n",
                name,
                Temperature.formatValue(stat.MinVal),
                Temperature.formatAverage(stat.Sum, stat.Count),
                Temperature.formatValue(stat.MaxVal)
            );
        }

        long elapsedNanos = System.nanoTime() - startTime;
        System.err.printf("Time taken: %d.%06d seconds%n",
                elapsedNanos / 1_000_000_000L,
                (elapsedNanos % 1_000_000_000L) / 1_000L);
    }

    private static final class OutputRow {
        final String name;
        final CountryStat stat;
        OutputRow(String name, CountryStat stat) { this.name = name; this.stat = stat; }
    }
}
