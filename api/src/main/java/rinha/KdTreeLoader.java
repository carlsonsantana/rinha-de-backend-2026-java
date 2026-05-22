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

public final class KdTreeLoader {

    private static final int MAGIC        = 'R' | 'N' << 8 | 'H' << 16 | 'A' << 24;
    private static final int DIMS         = 14;
    private static final int HEADER_BYTES = 32;

    /**
     * Parsed KD-Tree index ready for nearest-neighbour queries.
     * Points are stored off-heap (mmap) to stay outside the GC heap.
     * Layout: points[nodeId × DIMS + dim], little-endian shorts.
     * Labels/axis/children are small enough to live on-heap.
     */
    public record KdTreeData(
        int n,
        int root,
        MemorySegment points,   // off-heap: n × DIMS × 2 bytes, LE shorts
        byte[] labels,          // 0 = legit, 1 = fraud (permutation order)
        byte[] axis,            // split axis per node (0–13)
        int[] left,             // left child node IDs (−1 = none)
        int[] right             // right child node IDs (−1 = none)
    ) {
        private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        public short pointAt(int nodeId, int dim) {
            return points.get(LE_SHORT, ((long) nodeId * DIMS + dim) * 2);
        }
    }

    private static final ValueLayout.OfInt LE_INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Path indexPath;
    private volatile boolean loaded = false;
    private final AtomicReference<KdTreeData> data      = new AtomicReference<>();
    private final AtomicReference<Throwable>  loadError = new AtomicReference<>();

    public KdTreeLoader(Path indexPath) {
        this.indexPath = indexPath;
    }

    public void startLoading() {
        Thread t = new Thread(this::load, "kdtree-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Returns true once the index is fully parsed and ready to query. */
    public boolean isLoaded() {
        return loaded;
    }

    /** Returns the loaded index, or {@code null} if loading is not yet complete. */
    public KdTreeData getContent() {
        return data.get();
    }

    public Throwable getLoadError() {
        return loadError.get();
    }

    private void load() {
        try (FileChannel ch = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            // ofAuto() is GC-managed: the points MemorySegment keeps the arena
            // (and the mmap) alive as long as the KdTreeData is reachable.
            Arena arena = Arena.ofAuto();
            MemorySegment mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);

            ByteBuffer hdr = mapped.asSlice(0, HEADER_BYTES)
                                   .asByteBuffer()
                                   .order(ByteOrder.LITTLE_ENDIAN);
            int magic = hdr.getInt();
            /* version */ hdr.getInt();
            int n    = hdr.getInt();
            int dims = hdr.getInt();
            int root = hdr.getInt();

            if (magic != MAGIC) throw new IOException("bad magic: 0x" + Integer.toHexString(magic));
            if (dims  != DIMS)  throw new IOException("unexpected dims: " + dims);

            long pointsOff  = HEADER_BYTES;
            long pointsBytes = (long) n * DIMS * 2;
            long labelsOff  = pointsOff + pointsBytes;
            long axisOff    = labelsOff + n;
            long leftOff    = axisOff + n;
            long rightOff   = leftOff + (long) n * 4;

            MemorySegment points = mapped.asSlice(pointsOff, pointsBytes);
            byte[] labels = mapped.asSlice(labelsOff, n).toArray(ValueLayout.JAVA_BYTE);
            byte[] axis   = mapped.asSlice(axisOff,   n).toArray(ValueLayout.JAVA_BYTE);
            int[]  left   = mapped.asSlice(leftOff,  (long) n * 4).toArray(LE_INT);
            int[]  right  = mapped.asSlice(rightOff, (long) n * 4).toArray(LE_INT);

            data.set(new KdTreeData(n, root, points, labels, axis, left, right));
            loaded = true;
            System.out.printf("[kdtree] loaded %,d points from %s%n", n, indexPath);
        } catch (Throwable e) {
            loadError.set(e);
            System.err.println("[kdtree] load failed: " + e);
        }
    }
}
