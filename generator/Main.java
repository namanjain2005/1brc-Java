package generator;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Generates a 1BRC-style measurements file from the station names in
 * weather_stations.csv. Each row is "name;temperature" with one decimal digit
 * and a uniform temperature in [-99.9, 99.9], terminated by '\n'.
 *
 * Usage: java generator.Main <output file> <row count> [threads]
 */
public class Main {
    private static final int BUFFER_SIZE = 1 << 20;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java generator.Main <output file> <row count> [threads]");
            System.exit(2);
        }
        Path output = Path.of(args[0]);
        long rowCount = Long.parseLong(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Math.min(16,
                Runtime.getRuntime().availableProcessors());

        List<byte[]> names = readStationNames(Path.of("weather_stations.csv"));
        System.out.println("Loaded " + names.size() + " station names");

        long start = System.nanoTime();
        Path dir = output.toAbsolutePath().getParent();
        List<Path> segments = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        long rowsPerSegment = rowCount / threads;
        long emitted = 0;
        for (int i = 0; i < threads; i++) {
            long rows = (i == threads - 1) ? rowCount - emitted : rowsPerSegment;
            emitted += rows;
            Path segment = dir.resolve(output.getFileName() + ".seg" + i);
            segments.add(segment);
            futures.add(pool.submit(() -> {
                writeSegment(segment, names, rows);
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        // Assemble the final file from the segments, deleting each one as it is
        // appended so peak disk usage stays near the final size.
        try (FileChannel dest = FileChannel.open(output, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long position = 0;
            for (Path segment : segments) {
                try (FileChannel source = FileChannel.open(segment, StandardOpenOption.READ)) {
                    long size = source.size();
                    long copied = 0;
                    while (copied < size) {
                        long transferred = dest.transferFrom(source, position + copied,
                                size - copied);
                        if (transferred <= 0) {
                            throw new IOException("transferFrom stalled for " + segment);
                        }
                        copied += transferred;
                    }
                    position += size;
                }
                Files.delete(segment);
            }
        }

        long elapsed = System.nanoTime() - start;
        System.out.printf("Wrote %,d rows (%s) in %.1f s%n",
                rowCount, humanBytes(Files.size(output)), elapsed / 1e9);
    }

    private static void writeSegment(Path segment, List<byte[]> names, long rows)
            throws IOException {
        byte[][] nameArray = names.toArray(new byte[0][]);
        int nameCount = nameArray.length;
        try (BufferedOutputStream out = new BufferedOutputStream(
                Files.newOutputStream(segment), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int used = 0;
            java.util.random.RandomGenerator random =
                    java.util.random.RandomGenerator.of("L64X128MixRandom");
            for (long row = 0; row < rows; row++) {
                byte[] name = nameArray[random.nextInt(nameCount)];
                if (used + name.length + 16 > buffer.length) {
                    out.write(buffer, 0, used);
                    used = 0;
                }
                System.arraycopy(name, 0, buffer, used, name.length);
                used += name.length;
                buffer[used++] = ';';
                int tenths = random.nextInt(-999, 1000);
                if (tenths < 0) {
                    buffer[used++] = '-';
                    tenths = -tenths;
                }
                int whole = tenths / 10;
                int fraction = tenths % 10;
                if (whole >= 100) {
                    buffer[used++] = (byte) ('0' + whole / 100);
                    buffer[used++] = (byte) ('0' + whole / 10 % 10);
                } else if (whole >= 10) {
                    buffer[used++] = (byte) ('0' + whole / 10);
                }
                buffer[used++] = (byte) ('0' + whole % 10);
                buffer[used++] = '.';
                buffer[used++] = (byte) ('0' + fraction);
                buffer[used++] = '\n';
            }
            if (used > 0) {
                out.write(buffer, 0, used);
            }
        }
    }

    private static List<byte[]> readStationNames(Path stations) throws IOException {
        byte[] data = Files.readAllBytes(stations);
        List<byte[]> names = new ArrayList<>();
        int lineStart = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                int lineEnd = i;
                if (lineEnd > lineStart && data[lineEnd - 1] == '\r') {
                    lineEnd--;
                }
                int sep = lineStart;
                while (sep < lineEnd && data[sep] != ';') {
                    sep++;
                }
                if (sep > lineStart && sep < lineEnd) {
                    names.add(Arrays.copyOfRange(data, lineStart, sep));
                }
                lineStart = i + 1;
            }
        }
        return names;
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format("%.1f GiB", bytes / (double) (1L << 30));
        }
        return String.format("%.1f MiB", bytes / (double) (1L << 20));
    }
}
