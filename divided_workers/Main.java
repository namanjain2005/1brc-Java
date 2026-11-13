package divided_workers;

import util.CountryStat;
import util.Temperature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<Future<Map<String, CountryStat>>> futures = new ArrayList<>();

        for (int i = 0; i < noOfThreads; i++) {
            long start = i * chunkSize;
            long end = (i == noOfThreads - 1) ? fileSize : start + chunkSize;
            futures.add(pool.submit(new Worker(fileName, start, end)));
        }

        Map<String, CountryStat> aggregate = new HashMap<>();
        try {
            for (Future<Map<String, CountryStat>> future : futures) {
                for (Map.Entry<String, CountryStat> entry : future.get().entrySet()) {
                    CountryStat merged = aggregate.get(entry.getKey());
                    if (merged == null) {
                        aggregate.put(entry.getKey(), entry.getValue());
                    } else {
                        CountryStat stat = entry.getValue();
                        merged.Sum += stat.Sum;
                        merged.Count += stat.Count;
                        merged.MaxVal = Math.max(merged.MaxVal, stat.MaxVal);
                        merged.MinVal = Math.min(merged.MinVal, stat.MinVal);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Aggregation failed: " + e);
            pool.shutdownNow();
            return;
        } finally {
            pool.shutdown();
        }

        List<String> names = new ArrayList<>(aggregate.keySet());
        Collections.sort(names);
        for (String name : names) {
            CountryStat stat = aggregate.get(name);
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
}
