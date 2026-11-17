package divided_workers;

import util.CountryStat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import sun.misc.Unsafe;

/** Bounded, resizing open-addressed station table with selectable key storage. */
final class ByteStationTable {
    static final int DEFAULT_LIMIT = 100_000;
    private static final int DEFAULT_CAPACITY = 1 << 14;
    private static final int STORAGE_COPY = 0;
    private static final int STORAGE_ARENA = 1;
    private static final int STORAGE_MAPPED = 2;
    private static final int STORAGE = storageMode();
    private static final long MAP_WINDOW = 1L << 30;
    private static final Unsafe UNSAFE = initUnsafe();
    private static final long BUFFER_ADDRESS_OFFSET = bufferAddressOffset();
    private static volatile MappedByteBuffer[] mappedBuffers;
    private static volatile long[] mappedAddresses;

    private byte[][] keys;
    private int[] arenaOffsets;
    private long[] mappedOffsets;
    private byte[] keyLengths;
    private byte[] keyArena;
    private int arenaSize;
    private int[] hashes;
    private CountryStat[] values;
    private int mask;
    private final int limit;
    private int size;

    /** The input array supplies the initial bucket capacity, which must be a power of two. */
    ByteStationTable(byte[][] buckets) {
        this(buckets.length, DEFAULT_LIMIT, STORAGE);
    }

    static ByteStationTable createDefault() {
        return new ByteStationTable(DEFAULT_CAPACITY, DEFAULT_LIMIT, STORAGE);
    }

    static ByteStationTable createCopy() {
        return new ByteStationTable(DEFAULT_CAPACITY, DEFAULT_LIMIT, STORAGE_COPY);
    }

    static boolean mappedStorageEnabled() { return STORAGE == STORAGE_MAPPED; }

    static void mapInput(Path file) throws IOException {
        long size = java.nio.file.Files.size(file);
        int count = (int) ((size + MAP_WINDOW - 1) / MAP_WINDOW);
        MappedByteBuffer[] buffers = new MappedByteBuffer[count];
        long[] addresses = new long[count];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            for (int i = 0; i < count; i++) {
                long offset = i * MAP_WINDOW;
                long length = Math.min(MAP_WINDOW, size - offset);
                buffers[i] = channel.map(FileChannel.MapMode.READ_ONLY, offset, length);
                addresses[i] = UNSAFE.getLong(buffers[i], BUFFER_ADDRESS_OFFSET);
            }
        }
        mappedAddresses = addresses;
        mappedBuffers = buffers;
    }

    private ByteStationTable(int capacity, int limit, int storage) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (capacity < 2 || (capacity & (capacity - 1)) != 0)
            throw new IllegalArgumentException("bucket array length must be a power of two >= 2");
        if (storage == STORAGE_MAPPED && mappedAddresses == null)
            throw new IllegalStateException("mapInput must be called before creating mapped tables");
        this.keys = storage == STORAGE_COPY ? new byte[capacity][] : null;
        this.arenaOffsets = storage == STORAGE_ARENA ? new int[capacity] : null;
        this.mappedOffsets = storage == STORAGE_MAPPED ? new long[capacity] : null;
        this.keyLengths = new byte[capacity];
        this.keyArena = storage == STORAGE_ARENA ? new byte[1024] : null;
        this.hashes = new int[capacity];
        this.values = new CountryStat[capacity];
        this.mask = capacity - 1;
        this.limit = limit;
    }

    private static int storageMode() {
        String value = System.getProperty("key.storage", "copy");
        if ("arena".equals(value)) return STORAGE_ARENA;
        if ("mapped".equals(value)) return STORAGE_MAPPED;
        if ("copy".equals(value)) return STORAGE_COPY;
        throw new IllegalArgumentException("Unknown key.storage mode: " + value);
    }

    CountryStat get(byte[] data, int from, int to) {
        int hash = hash(data, from, to);
        int slot = hash & mask;
        for (int probes = 0; probes < keyLengths.length; probes++) {
            if (keyLengths[slot] == 0) return null;
            if (hashes[slot] == hash && equalsAt(slot, data, from, to)) {
                return values[slot];
            }
            slot = (slot + 1) & mask;
        }
        return null;
    }

    void put(byte[] data, int from, int to, CountryStat value) {
        put(data, from, to, value, -1);
    }

    void put(byte[] data, int from, int to, CountryStat value, long fileOffset) {
        int hash = hash(data, from, to);
        while (true) {
            int slot = hash & mask;
            int probes = 0;
            for (; probes < keyLengths.length; probes++) {
                if (keyLengths[slot] == 0) break;
                if (hashes[slot] == hash && equalsAt(slot, data, from, to)) {
                    values[slot] = value;
                    return;
                }
                slot = (slot + 1) & mask;
            }

            if (size >= limit) throw new IllegalStateException("Station table limit reached: " + limit);
            if (probes == keyLengths.length || size + 1 > keyLengths.length * 3 / 4) {
                resize();
                continue;
            }
            storeKey(slot, data, from, to, fileOffset);
            hashes[slot] = hash;
            values[slot] = value;
            size++;
            return;
        }
    }

    void merge(ByteStationTable other) {
        for (int i = 0; i < other.capacity(); i++) {
            byte[] key = other.keyAt(i);
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

    private void storeKey(int slot, byte[] data, int from, int to, long fileOffset) {
        int length = to - from;
        if (length == 0 || length > 255) throw new IllegalArgumentException("Unsupported station name length: " + length);
        keyLengths[slot] = (byte) length;
        if (keys != null) {
            keys[slot] = Arrays.copyOfRange(data, from, to);
        } else if (arenaOffsets != null) {
            ensureArena(length);
            arenaOffsets[slot] = arenaSize;
            System.arraycopy(data, from, keyArena, arenaSize, length);
            arenaSize += length;
        } else {
            if (fileOffset < 0) throw new IllegalArgumentException("mapped keys need a file offset");
            mappedOffsets[slot] = fileOffset;
        }
    }

    private void ensureArena(int needed) {
        if (keyArena.length - arenaSize >= needed) return;
        int capacity = keyArena.length;
        while (capacity - arenaSize < needed) capacity = Math.max(capacity << 1, arenaSize + needed);
        keyArena = Arrays.copyOf(keyArena, capacity);
    }

    private boolean equalsAt(int slot, byte[] data, int from, int to) {
        int length = to - from;
        if ((keyLengths[slot] & 0xff) != length) return false;
        if (keys != null) return equals(keys[slot], data, from, to);
        if (arenaOffsets != null) {
            int offset = arenaOffsets[slot];
            for (int i = 0; i < length; i++) if (keyArena[offset + i] != data[from + i]) return false;
            return true;
        }
        return mappedEquals(mappedOffsets[slot], data, from, length);
    }

    private static boolean mappedEquals(long fileOffset, byte[] data, int from, int length) {
        long windowStart = fileOffset & -MAP_WINDOW;
        int window = (int) (fileOffset / MAP_WINDOW);
        int within = (int) (fileOffset - windowStart);
        MappedByteBuffer[] buffers = mappedBuffers;
        if (window >= buffers.length || within + length > buffers[window].capacity()) {
            for (int i = 0; i < length; i++) if (mappedByte(fileOffset + i) != data[from + i]) return false;
            return true;
        }
        long address = mappedAddresses[window] + within;
        for (int i = 0; i < length; i++) if (UNSAFE.getByte(address + i) != data[from + i]) return false;
        return true;
    }

    private static byte mappedByte(long offset) {
        int window = (int) (offset / MAP_WINDOW);
        int within = (int) (offset % MAP_WINDOW);
        return UNSAFE.getByte(mappedAddresses[window] + within);
    }

    int size() { return size; }
    int capacity() { return keyLengths.length; }

    byte[] keyAt(int slot) {
        if (keyLengths[slot] == 0) return null;
        if (keys != null) return keys[slot];
        int length = keyLengths[slot] & 0xff;
        byte[] result = new byte[length];
        if (arenaOffsets != null) {
            System.arraycopy(keyArena, arenaOffsets[slot], result, 0, length);
        } else {
            for (int i = 0; i < length; i++) result[i] = mappedByte(mappedOffsets[slot] + i);
        }
        return result;
    }

    CountryStat valueAt(int slot) { return values[slot]; }

    private static int hash(byte[] data, int from, int to) {
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
        if (keyLengths.length >= (1 << 29)) throw new IllegalStateException("Station table is too large to resize");
        byte[][] oldKeys = keys;
        int[] oldArenaOffsets = arenaOffsets;
        long[] oldMappedOffsets = mappedOffsets;
        byte[] oldLengths = keyLengths;
        int[] oldHashes = hashes;
        CountryStat[] oldValues = values;
        int newCapacity = oldLengths.length << 1;
        keys = oldKeys == null ? null : new byte[newCapacity][];
        arenaOffsets = oldArenaOffsets == null ? null : new int[newCapacity];
        mappedOffsets = oldMappedOffsets == null ? null : new long[newCapacity];
        keyLengths = new byte[newCapacity];
        hashes = new int[newCapacity];
        values = new CountryStat[newCapacity];
        mask = newCapacity - 1;

        for (int i = 0; i < oldLengths.length; i++) {
            if (oldLengths[i] == 0) continue;
            int slot = oldHashes[i] & mask;
            while (keyLengths[slot] != 0) slot = (slot + 1) & mask;
            keyLengths[slot] = oldLengths[i];
            if (keys != null) keys[slot] = oldKeys[i];
            if (arenaOffsets != null) arenaOffsets[slot] = oldArenaOffsets[i];
            if (mappedOffsets != null) mappedOffsets[slot] = oldMappedOffsets[i];
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

    String nameAt(int slot) { return new String(keyAt(slot), StandardCharsets.UTF_8); }

    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long bufferAddressOffset() {
        try {
            return UNSAFE.objectFieldOffset(java.nio.Buffer.class.getDeclaredField("address"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
