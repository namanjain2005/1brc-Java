package divided_workers;

import util.CountryStat;
import util.Temperature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

class Worker implements Callable<Map<String, CountryStat>> {
    private static final int BUFFER_SIZE = 1 << 16;

    private final long start;
    private final long end;
    private final String fileName;

    Worker(String fileName, long start, long end) {
        this.start = start;
        this.end = end;
        this.fileName = fileName;
    }

    @Override
    public Map<String, CountryStat> call() throws Exception {
        Map<String, CountryStat> local = new HashMap<>();
        try (RandomAccessFile file = new RandomAccessFile(fileName, "r")) {
            long pos = start;
            if (start != 0) {
                // Only skip the first line when the chunk starts in the middle of it.
                // If the previous byte is a newline, the chunk already begins on a
                // line boundary and skipping would drop a valid measurement.
                file.seek(start - 1);
                if (file.read() != '\n') {
                    LineReader reader = new LineReader(file, start);
                    reader.skipLine();
                    pos = reader.position();
                }
            }

            LineReader reader = new LineReader(file, pos);
            while (reader.position() < end) {
                byte[] line = reader.readLine();
                if (line == null) {
                    break;
                }
                process(line, local);
            }
        }
        return local;
    }

    private void process(byte[] rawLine, Map<String, CountryStat> local) {
        // RandomAccessFile.readLine() decodes as ISO-8859-1, which corrupts the
        // UTF-8 station names, so decode the raw bytes explicitly as UTF-8.
        String line = new String(rawLine, StandardCharsets.UTF_8);
        int sep = line.indexOf(';');
        if (sep < 0) {
            return;
        }
        String name = line.substring(0, sep);
        long val = Temperature.parseScaled(line.substring(sep + 1));
        CountryStat stat = local.get(name);
        if (stat == null) {
            local.put(name, new CountryStat(val));
        } else {
            stat.update(val);
        }
    }

    /**
     * Buffered reader over a byte range of the file. Unlike RandomAccessFile.readLine()
     * it keeps the raw bytes so lines can be decoded as UTF-8.
     */
    private static final class LineReader {
        private final RandomAccessFile file;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private long pos;
        private int buffered;
        private int offset;

        LineReader(RandomAccessFile file, long pos) throws IOException {
            this.file = file;
            this.pos = pos;
            fill();
        }

        long position() {
            return pos;
        }

        private void fill() throws IOException {
            file.seek(pos);
            buffered = file.read(buffer);
            offset = 0;
            if (buffered < 0) {
                buffered = 0;
            }
        }

        /**
         * Reads the next line as raw bytes without its terminator, or null at EOF.
         * A line starting before the chunk end is always read to completion, even if
         * its terminator lies beyond the end; the next worker skips that remainder.
         */
        byte[] readLine() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                if (offset >= buffered) {
                    fill();
                    if (buffered == 0) {
                        return out.size() == 0 ? null : out.toByteArray();
                    }
                }
                int end = offset;
                while (end < buffered && buffer[end] != '\n') {
                    end++;
                }
                if (end < buffered) {
                    out.write(buffer, offset, end - offset);
                    pos += end + 1 - offset;
                    offset = end + 1;
                    byte[] line = out.toByteArray();
                    int length = line.length;
                    if (length > 0 && line[length - 1] == '\r') {
                        byte[] trimmed = new byte[length - 1];
                        System.arraycopy(line, 0, trimmed, 0, length - 1);
                        return trimmed;
                    }
                    return line;
                }
                out.write(buffer, offset, buffered - offset);
                pos += buffered - offset;
                offset = buffered;
            }
        }

        void skipLine() throws IOException {
            while (true) {
                if (offset >= buffered) {
                    fill();
                    if (buffered == 0) {
                        return;
                    }
                }
                int end = offset;
                while (end < buffered && buffer[end] != '\n') {
                    end++;
                }
                if (end < buffered) {
                    pos += end + 1 - offset;
                    offset = end + 1;
                    return;
                }
                pos += buffered - offset;
                offset = buffered;
            }
        }
    }
}
