package divided_workers;

import util.CountryStat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Fixed-capacity open-addressed table keyed by UTF-8 byte sequences. */
final class ByteStationTable {
    static final int DEFAULT_LIMIT = 100_000;

    private final byte[][] keys;
    private final int[] hashes;
    private final CountryStat[] values;
    private final int mask;
    private final int limit;
    private int size;

    /** The input array supplies the table's fixed bucket capacity (rounded up to a power of two). */
    ByteStationTable(byte[][] buckets) {
        this(buckets, DEFAULT_LIMIT);
    }

    static ByteStationTable createDefault() {
        return new ByteStationTable(new byte[1 << 17][]);
    }

    ByteStationTable(byte[][] buckets, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (buckets.length < 2 || (buckets.length & (buckets.length - 1)) != 0)
            throw new IllegalArgumentException("bucket array length must be a power of two >= 2");
        this.keys = buckets;
        this.hashes = new int[buckets.length];
        this.values = new CountryStat[buckets.length];
        this.mask = buckets.length - 1;
        this.limit = Math.min(limit, buckets.length * 3 / 4);
    }

    void update(byte[] data, int from, int to, long temperature) {
        int hash = hash(data, from, to);
        int slot = hash & mask;
        while (keys[slot] != null) {
            if (hashes[slot] == hash && equals(keys[slot], data, from, to)) {
                values[slot].update(temperature);
                return;
            }
            slot = (slot + 1) & mask;
        }
        if (size >= limit) throw new IllegalStateException("Station table limit reached: " + limit);
        keys[slot] = Arrays.copyOfRange(data, from, to);
        hashes[slot] = hash;
        values[slot] = new CountryStat(temperature);
        size++;
    }

    void merge(ByteStationTable other) {
        for (int i = 0; i < other.keys.length; i++) {
            byte[] key = other.keys[i];
            if (key == null) continue;
            CountryStat value = other.values[i];
            int slot = other.hashes[i] & mask;
            while (keys[slot] != null && !(hashes[slot] == other.hashes[i]
                    && equals(keys[slot], key, 0, key.length))) {
                slot = (slot + 1) & mask;
            }
            if (keys[slot] == null) {
                if (size >= limit) throw new IllegalStateException("Station table limit reached: " + limit);
                keys[slot] = Arrays.copyOf(key, key.length);
                hashes[slot] = other.hashes[i];
                values[slot] = new CountryStat(value.Sum);
                values[slot].Count = value.Count;
                values[slot].MinVal = value.MinVal;
                values[slot].MaxVal = value.MaxVal;
                size++;
            } else {
                CountryStat current = values[slot];
                current.Sum += value.Sum;
                current.Count += value.Count;
                current.MinVal = Math.min(current.MinVal, value.MinVal);
                current.MaxVal = Math.max(current.MaxVal, value.MaxVal);
            }
        }
    }

    int size() { return size; }
    byte[] keyAt(int slot) { return keys[slot]; }
    CountryStat valueAt(int slot) { return values[slot]; }

    private static int hash(byte[] data, int from, int to) {
        // Hash length plus the first and last bytes, then mix all bytes to limit
        // clustering when station names share prefixes or suffixes.
        int h = 0x811c9dc5 ^ (to - from);
        for (int i = from; i < to; i++) h = (h ^ (data[i] & 0xff)) * 0x01000193;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h;
    }

    private static boolean equals(byte[] key, byte[] data, int from, int to) {
        int len = to - from;
        if (key.length != len) return false;
        for (int i = 0; i < len; i++) if (key[i] != data[from + i]) return false;
        return true;
    }

    String nameAt(int slot) { return new String(keys[slot], StandardCharsets.UTF_8); }
}
