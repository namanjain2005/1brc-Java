package divided_workers;

import util.CountryStat;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

class Worker implements Callable<ByteStationTable> {
    private static final int BUFFER_SIZE = 1 << 16;
    private static final byte NEWLINE = '\n';
    private static final byte SEMICOLON = ';';
    // Mirrors the private Temperature.UNITS_PER_DEGREE so temperatures can be
    // parsed straight from the raw line bytes without a String copy.
    private static final long UNITS_PER_DEGREE = 10_000;

    // SIMD byte search when the incubator Vector API module is enabled
    // (--add-modules jdk.incubator.vector), scalar loop otherwise.
    private static final LineScanner SCANNER = ScannerHolder.SCANNER;

    private final long start;
    private final long end;
    private final String fileName;

    Worker(String fileName, long start, long end) {
        this.start = start;
        this.end = end;
        this.fileName = fileName;
    }

    @Override
    public ByteStationTable call() throws Exception {
        ByteStationTable local = ByteStationTable.createDefault();
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
            while (reader.position() < end && reader.nextLine()) {
                process(reader.lineBytes, reader.lineStart, reader.lineEnd, local);
            }
        }
        return local;
    }

    private void process(byte[] data, int from, int to, ByteStationTable local) {
        int sep = SCANNER.indexOf(data, from, to, SEMICOLON);
        if (sep < 0) {
            return;
        }
        long val = parseScaled(data, sep + 1, to);
        local.update(data, from, sep, val);
    }

    private static long parseScaled(byte[] buf, int from, int to) {
        int index = from;
        boolean negative = false;
        if (index < to && (buf[index] == '-' || buf[index] == '+')) {
            negative = buf[index] == '-';
            index++;
        }

        long whole = 0;
        int wholeDigits = 0;
        while (index < to && buf[index] != '.') {
            byte digit = buf[index++];
            if (digit < '0' || digit > '9') {
                throw new NumberFormatException("Invalid temperature: " + valueText(buf, from, to));
            }
            whole = whole * 10 + (digit - '0');
            wholeDigits++;
        }
        if (wholeDigits == 0) {
            throw new NumberFormatException("Invalid temperature: " + valueText(buf, from, to));
        }

        long fraction = 0;
        int fractionalDigits = 0;
        if (index < to) {
            index++;
            if (index == to) {
                throw new NumberFormatException("Invalid temperature: " + valueText(buf, from, to));
            }
            while (index < to) {
                byte digit = buf[index++];
                if (digit < '0' || digit > '9' || fractionalDigits == 4) {
                    throw new NumberFormatException("Invalid temperature: " + valueText(buf, from, to));
                }
                fraction = fraction * 10 + (digit - '0');
                fractionalDigits++;
            }
        }
        while (fractionalDigits++ < 4) {
            fraction *= 10;
        }

        long scaled = whole * UNITS_PER_DEGREE + fraction;
        return negative ? -scaled : scaled;
    }

    private static String valueText(byte[] buf, int from, int to) {
        return new String(buf, from, to - from, StandardCharsets.UTF_8);
    }

    /** Finds a byte in a range; SIMD when the incubator Vector API is available. */
    private interface LineScanner {
        /** First index of needle in data[from, to), or -1 when absent. */
        int indexOf(byte[] data, int from, int to, byte needle);
    }

    private static final class ScannerHolder {
        static final LineScanner SCANNER = pick();

        private static LineScanner pick() {
            try {
                // Initializing the class fails unless the incubator vector module
                // is enabled; fall back to the scalar scan in that case.
                Class.forName(VectorScanner.class.getName(), true,
                        VectorScanner.class.getClassLoader());
                return new VectorScanner();
            } catch (Throwable unavailable) {
                return Worker::scalarIndexOf;
            }
        }
    }

    static int scalarIndexOf(byte[] data, int from, int to, byte needle) {
        for (int i = from; i < to; i++) {
            if (data[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    /** SIMD version, kept in its own class so a missing incubator module only
        fails when this class initializes, which ScannerHolder catches. */
    private static final class VectorScanner implements LineScanner {
        private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

        @Override
        public int indexOf(byte[] data, int from, int to, byte needle) {
            int i = from;
            int vectorEnd = to - SPECIES.length();
            for (; i <= vectorEnd; i += SPECIES.length()) {
                ByteVector chunk = ByteVector.fromArray(SPECIES, data, i);
                int hit = chunk.eq(needle).firstTrue();
                if (hit < SPECIES.length()) {
                    return i + hit;
                }
            }
            if (i < to) {
                VectorMask<Byte> valid = SPECIES.indexInRange(i, to);
                ByteVector chunk = ByteVector.fromArray(SPECIES, data, i, valid);
                int hit = chunk.eq(needle).and(valid).firstTrue();
                if (hit < to - i) {
                    return i + hit;
                }
            }
            return -1;
        }
    }

    /**
     * Buffered reader over a byte range of the file. Exposes each line as a
     * view: when the line lies inside the read buffer the view points into it
     * (no per-line allocation), lines that cross a refill are assembled into a
     * reused spare array. Unlike RandomAccessFile.readLine() the raw bytes are
     * kept so the name can be decoded as UTF-8.
     */
    private static final class LineReader {
        private final RandomAccessFile file;
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private byte[] spare = new byte[256];
        private long pos;
        private int buffered;
        private int offset;

        // View of the last line returned by nextLine().
        byte[] lineBytes;
        int lineStart;
        int lineEnd;

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
         * Advances to the next line, leaving its raw bytes (terminator
         * excluded) in lineBytes[lineStart, lineEnd). Returns false at EOF.
         * A line starting before the chunk end is always read to completion,
         * even if its terminator lies beyond the end; the next worker skips
         * that remainder.
         */
        boolean nextLine() throws IOException {
            if (offset >= buffered) {
                fill();
                if (buffered == 0) {
                    return false;
                }
            }
            int found = SCANNER.indexOf(buffer, offset, buffered, NEWLINE);
            if (found >= 0) {
                lineBytes = buffer;
                lineStart = offset;
                lineEnd = found;
                pos += found + 1 - offset;
                offset = found + 1;
                trimCarriageReturn();
                return true;
            }
            return slowLine();
        }

        private void trimCarriageReturn() {
            if (lineEnd > lineStart && lineBytes[lineEnd - 1] == '\r') {
                lineEnd--;
            }
        }

        /** Collects a line that spans one or more buffer refills. */
        private boolean slowLine() throws IOException {
            int length = buffered - offset;
            spare = ensureCapacity(spare, length);
            System.arraycopy(buffer, offset, spare, 0, length);
            pos += length;
            offset = buffered;
            while (true) {
                fill();
                if (buffered == 0) {
                    lineBytes = spare;
                    lineStart = 0;
                    lineEnd = length;
                    trimCarriageReturn();
                    return true;
                }
                int found = SCANNER.indexOf(buffer, 0, buffered, NEWLINE);
                if (found >= 0) {
                    spare = ensureCapacity(spare, length + found);
                    System.arraycopy(buffer, 0, spare, length, found);
                    length += found;
                    pos += found + 1;
                    offset = found + 1;
                    lineBytes = spare;
                    lineStart = 0;
                    lineEnd = length;
                    trimCarriageReturn();
                    return true;
                }
                spare = ensureCapacity(spare, length + buffered);
                System.arraycopy(buffer, 0, spare, length, buffered);
                length += buffered;
                pos += buffered;
            }
        }

        private static byte[] ensureCapacity(byte[] array, int needed) {
            if (array.length >= needed) {
                return array;
            }
            int capacity = array.length;
            while (capacity < needed) {
                capacity <<= 1;
            }
            return Arrays.copyOf(array, capacity);
        }

        void skipLine() throws IOException {
            while (true) {
                if (offset >= buffered) {
                    fill();
                    if (buffered == 0) {
                        return;
                    }
                }
                int found = SCANNER.indexOf(buffer, offset, buffered, NEWLINE);
                if (found >= 0) {
                    pos += found + 1 - offset;
                    offset = found + 1;
                    return;
                }
                pos += buffered - offset;
                offset = buffered;
            }
        }
    }
}
