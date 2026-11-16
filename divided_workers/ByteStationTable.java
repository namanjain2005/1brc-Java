package divided_workers;

import util.CountryStat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, resizing open-addressed table keyed by UTF-8 byte sequences. */
final class ByteStationTable {
    static final int DEFAULT_LIMIT = 100_000;
    private static final int DEFAULT_CAPACITY = 1 << 14;

    private byte[][] keys;
    private int[] hashes;
    private CountryStat[] values;
    private int mask;
    private final int limit;
    private int size;

    /** The input array supplies the initial bucket capacity, which must be a power of two. */
    ByteStationTable(byte[][] buckets) {
        this(buckets, DEFAULT_LIMIT);
    }

    static ByteStationTable createDefault() {
        return new ByteStationTable(new byte[DEFAULT_CAPACITY][]);
    }

    ByteStationTable(byte[][] buckets, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (buckets.length < 2 || (buckets.length & (buckets.length - 1)) != 0)
            throw new IllegalArgumentException("bucket array length must be a power of two >= 2");
        this.keys = buckets;
        this.hashes = new int[buckets.length];
        this.values = new CountryStat[buckets.length];
        this.mask = buckets.length - 1;
        this.limit = limit;
    }

    CountryStat get(byte[] data, int from, int to) {
        int hash = hash(data, from, to);
        int slot = hash & mask;
        for (int probes = 0; probes < keys.length; probes++) {
            if (keys[slot] == null) return null;
            if (hashes[slot] == hash && equals(keys[slot], data, from, to)) {
                return values[slot];
            }
            slot = (slot + 1) & mask;
        }
        return null;
    }

    void put(byte[] data, int from, int to, CountryStat value) {
        int hash = hash(data, from, to);
        while (true) {
            int slot = hash & mask;
            int probes = 0;
            for (; probes < keys.length; probes++) {
                if (keys[slot] == null) break;
                if (hashes[slot] == hash && equals(keys[slot], data, from, to)) {
                    values[slot] = value;
                    return;
                }
                slot = (slot + 1) & mask;
            }

            if (size >= limit) throw new IllegalStateException("Station table limit reached: " + limit);
            if (probes == keys.length || size + 1 > keys.length * 3 / 4) {
                resize();
                continue;
            }
            keys[slot] = Arrays.copyOfRange(data, from, to);
            hashes[slot] = hash;
            values[slot] = value;
            size++;
            return;
        }
    }

    void merge(ByteStationTable other) {
        for (int i = 0; i < other.keys.length; i++) {
            byte[] key = other.keys[i];
            if (key == null) continue;
            CountryStat value = other.values[i];
            CountryStat current = get(key, 0, key.length);
            if (current == null) {
                CountryStat copy = new CountryStat(value.Sum);
                copy.Count = value.Count;
                copy.MinVal = value.MinVal;
                copy.MaxVal = value.MaxVal;
                put(key, 0, key.length, copy);
            } else {
                current.Sum += value.Sum;
                current.Count += value.Count;
                current.MinVal = Math.min(current.MinVal, value.MinVal);
                current.MaxVal = Math.max(current.MaxVal, value.MaxVal);
            }
        }
    }

    int size() { return size; }
    int capacity() { return keys.length; }
    byte[] keyAt(int slot) { return keys[slot]; }
    CountryStat valueAt(int slot) { return values[slot]; }

    private static int hash(byte[] data, int from, int to) {
        // Mix the name length and up to four bytes from each end. Station names
        // are short, and this keeps the hot-path hash cheaper than scanning all bytes.
        int length = to - from;
        int h = length;
        int prefixEnd = from + Math.min(length, 4);
        for (int i = from; i < prefixEnd; i++) h = 31 * h + (data[i] & 0xff);
        int suffixStart = from + Math.max(4, length - 4);
        for (int i = suffixStart; i < to; i++) h = 31 * h + (data[i] & 0xff);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h;
    }

    private void resize() {
        if (keys.length >= (1 << 29)) throw new IllegalStateException("Station table is too large to resize");
        byte[][] oldKeys = keys;
        int[] oldHashes = hashes;
        CountryStat[] oldValues = values;
        keys = new byte[oldKeys.length << 1][];
        hashes = new int[keys.length];
        values = new CountryStat[keys.length];
        mask = keys.length - 1;

        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] == null) continue;
            int slot = oldHashes[i] & mask;
            while (keys[slot] != null) slot = (slot + 1) & mask;
            keys[slot] = oldKeys[i];
            hashes[slot] = oldHashes[i];
            values[slot] = oldValues[i];
        }
    }

    private static boolean equals(byte[] key, byte[] data, int from, int to) {
        int len = to - from;
        if (key.length != len) return false;
        for (int i = 0; i < len; i++) if (key[i] != data[from + i]) return false;
        return true;
    }

    String nameAt(int slot) { return new String(keys[slot], StandardCharsets.UTF_8); }
}
