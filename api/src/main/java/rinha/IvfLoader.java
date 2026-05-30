package rinha;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

public final class IvfLoader {

    private static final int MAGIC        = 'R' | 'N' << 8 | 'H' << 16 | 'A' << 24;
    private static final int VERSION      = 3;
    private static final int DIMS         = 14;
    private static final int DIMS_STORED  = 16;
    private static final int HEADER_BYTES = 40;

    /**
     * Parsed IVF index ready for approximate top-k queries.
     * Centroids and points are off-heap (mmap'd) so they don't count
     * against the JVM heap; cluster offsets are tiny and stay on-heap.
     */
    public record IvfData(
        int n,
        int k,
        int maxClusterSize,
        MemorySegment centroids,    // k × DIMS_STORED × 2 bytes, LE shorts
        int[]         clusterOffset, // k + 1 ints (prefix sum); cluster c spans [offset[c], offset[c+1])
        MemorySegment points,        // n × DIMS_STORED × 2 bytes, LE shorts, cluster-ordered
        byte[]        labels         // n bytes, cluster-ordered
    ) {}

    private static final ValueLayout.OfInt LE_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Path indexPath;
    private volatile boolean loaded = false;
    private final AtomicReference<IvfData>   data      = new AtomicReference<>();
    private final AtomicReference<Throwable> loadError = new AtomicReference<>();

    public IvfLoader(Path indexPath) {
        this.indexPath = indexPath;
    }

    public void startLoading() {
        Thread t = new Thread(this::load, "ivf-loader");
        t.setDaemon(true);
        t.start();
    }

    public boolean isLoaded() {
        return loaded;
    }

    public IvfData getContent() {
        return data.get();
    }

    public Throwable getLoadError() {
        return loadError.get();
    }

    private void load() {
        try (FileChannel ch = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            Arena arena = Arena.ofAuto();
            MemorySegment mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);

            ByteBuffer hdr = mapped.asSlice(0, HEADER_BYTES)
                                   .asByteBuffer()
                                   .order(ByteOrder.LITTLE_ENDIAN);
            int magic   = hdr.getInt();
            int version = hdr.getInt();
            int n       = hdr.getInt();
            int dims    = hdr.getInt();
            int k       = hdr.getInt();
            int maxClusterSize = hdr.getInt();

            if (magic   != MAGIC)   throw new IOException("bad magic: 0x" + Integer.toHexString(magic));
            if (version != VERSION) throw new IOException("unsupported index version: " + version);
            if (dims    != DIMS)    throw new IOException("unexpected dims: " + dims);

            long centroidsOff   = HEADER_BYTES;
            long centroidsBytes = (long) k * DIMS_STORED * 2;
            long offsetsOff     = centroidsOff + centroidsBytes;
            long offsetsBytes   = (long)(k + 1) * 4;
            long pointsOff      = offsetsOff + offsetsBytes;
            long pointsBytes    = (long) n * DIMS_STORED * 2;
            long labelsOff      = pointsOff + pointsBytes;

            MemorySegment centroids = mapped.asSlice(centroidsOff, centroidsBytes);
            int[]         offset    = mapped.asSlice(offsetsOff, offsetsBytes).toArray(LE_INT);
            MemorySegment points    = mapped.asSlice(pointsOff, pointsBytes);
            byte[]        labels    = mapped.asSlice(labelsOff, n).toArray(ValueLayout.JAVA_BYTE);

            /* Pre-fault mmap'd pages before marking ready.
             * Centroids (65 KB, 16 pages): scanned on every query — must be warm.
             * Points (96 MB): sequential prefetch eliminates per-query page-fault spikes. */
            for (long off = 0; off < centroidsBytes; off += 4096)
                centroids.get(ValueLayout.JAVA_SHORT, off);
            for (long off = 0; off < pointsBytes; off += 4096)
                points.get(ValueLayout.JAVA_SHORT, off);

            data.set(new IvfData(n, k, maxClusterSize, centroids, offset, points, labels));
            loaded = true;
            System.out.printf("[ivf] loaded %,d points / %,d clusters from %s%n",
                n, k, indexPath);
        } catch (Throwable e) {
            loadError.set(e);
            System.err.println("[ivf] load failed: " + e);
        }
    }
}
