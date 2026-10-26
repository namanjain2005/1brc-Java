import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        long startTime = System.nanoTime();

        try (BufferedReader reader = new BufferedReader(
                new FileReader("weather_stations.csv", StandardCharsets.UTF_8))) {
            var allStats = reader.lines()
                    .parallel()
                    .collect(Collectors.groupingBy(
                            line -> line.substring(0, line.indexOf(';')),
                            Collectors.summarizingLong(line -> Temperature.parseScaled(
                                    line.substring(line.indexOf(';') + 1)))));

            var result = allStats.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> {
                        var stats = entry.getValue();
                        return Temperature.formatValue(stats.getMin()) + ";"
                                + Temperature.formatAverage(stats.getSum(), stats.getCount()) + ";"
                                + Temperature.formatValue(stats.getMax());
                    },
                    (left, right) -> right,
                    TreeMap::new));

            for (var entry : result.entrySet()) {
                System.out.println(entry.getKey() + ";" + entry.getValue());
            }
        } catch (IOException e) {
            System.err.format("IOException: %s%n", e);
        }

        long elapsedNanos = System.nanoTime() - startTime;
        System.err.printf("Time taken: %d.%06d seconds%n",
                elapsedNanos / 1_000_000_000L,
                (elapsedNanos % 1_000_000_000L) / 1_000L);
    }
}
