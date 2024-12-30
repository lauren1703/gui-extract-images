package selector;

import java.awt.Point;
import java.awt.Polygon;
import java.util.Arrays;

/**
 * A mutable buffer of points for building up a PolyLine one point at a time.
 */
public class PolyLineBuffer {

    /**
     * X coordinates of points in the buffer, contained in `xs[..size)`.  The length of this array
     * is the current "capacity" of the buffer.  Invariant: `xs.length == ys.length`.
     */
    private int[] xs;

    /**
     * Y coordinates of points in the buffer, contained in `ys[..size)`.
     */
    private int[] ys;

    /**
     * Number of points currently in the buffer.
     */
    private int size;

    /**
     * Create an empty PolyLineBuffer with default capacity (suitable for general-purpose use).
     */
    public PolyLineBuffer() {
        this(32);
    }

    /**
     * Create an empty PolyLineBuffer with an initial capacity of `initialCapacity`.  If the
     * approximate size of the PolyLine is known ahead of time, choosing a sufficiently large
     * initial capacity can reduce allocation and copying overhead from resizing.
     */
    public PolyLineBuffer(int initialCapacity) {
        assert initialCapacity > 0;
        xs = new int[initialCapacity];
        ys = new int[initialCapacity];
        size = 0;
    }

    /**
     * Append the point `p` to the end of this buffer if it is distinct from the buffer's current
     * endpoint.  Its coordinate values are copied, avoiding rep exposure.
     */
    public void append(Point p) {
        append(p.x, p.y);
    }

    /**
     * Append the point `(x, y)` to the end of this buffer if it is distinct from the buffer's
     * current endpoint.
     */
    public void append(int x, int y) {
        // Reject duplicates
        if (size > 0 && x == xs[size - 1] && y == ys[size - 1]) {
            return;
        }

        if (size == xs.length) {
            assert xs.length > 0;
            xs = Arrays.copyOf(xs, 2 * size);
            ys = Arrays.copyOf(ys, 2 * size);
        }
        xs[size] = x;
        ys[size] = y;
        size += 1;
    }

    public int[] xs() {
        return xs;
    }

    public int[] ys() {
        return ys;
    }

    /**
     * Return the number of points currently in this buffer.
     */
    public int size() {
        return size;
    }

    /**
     * Return a copy of the first point in this buffer.  Requires `size() > 0`.
     */
    public Point start() {
        assert size > 0;
        return new Point(xs[0], ys[0]);
    }

    /**
     * Return a copy of the last point in this buffer.  Requires `size() > 0`.
     */
    public Point end() {
        assert size > 0;
        return new Point(xs[size - 1], ys[size - 1]);
    }

    /**
     * Reverses the sequence of points in this buffer, then returns a reference to itself.
     */
    public PolyLineBuffer reverse() {
        int tmp;
        for (int i = 0; i < size / 2; ++i) {
            tmp = xs[i];
            xs[i] = xs[size - 1 - i];
            xs[size - 1 - i] = tmp;

            tmp = ys[i];
            ys[i] = ys[size - 1 - i];
            ys[size - 1 - i] = tmp;
        }
        return this;
    }

    /**
     * Return a PolyLine consisting of the points currently in this buffer.  Throws
     * `IllegalStateException` if the buffer is empty.
     */
    public PolyLine toPolyLine() throws IllegalStateException {
        if (size == 0) {
            throw new IllegalStateException("PolyLineBuffer is empty");
        } else if (size == 1) {
            Point p = new Point(xs[0], ys[0]);
            return new PolyLine(p, p);
        }
        return new PolyLine(Arrays.copyOf(xs, size), Arrays.copyOf(ys, size));
    }

    public static Polygon makePolygon(Iterable<PolyLineBuffer> segments) {
        Polygon poly = new Polygon();
        for (PolyLineBuffer pl : segments) {
            // FIXME: Dedup
            for (int i = 0; i < pl.size; ++i) {
                poly.addPoint(pl.xs[i], pl.ys[i]);
            }
        }
        return poly;
    }
}
