package seg.jUCMNav.importexport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Graphviz's {@code -Tplain} output, parsed.
 *
 * <p>
 * The format is four line kinds and nothing else:
 *
 * <pre>
 * graph SCALE WIDTH HEIGHT
 * node  NAME X Y WIDTH HEIGHT LABEL STYLE SHAPE COLOUR FILLCOLOUR
 * edge  TAIL HEAD N X1 Y1 ... Xn Yn [LABEL XL YL] STYLE COLOUR
 * stop
 * </pre>
 *
 * Whitespace-separated, one record per line, coordinates in inches with the origin at the
 * bottom left. It is documented as an output format for other programs to consume and has not
 * changed in twenty years, which is the entire reason for using it.
 *
 * <p>
 * What it replaces was a scrape of {@code -Tdot} -- Graphviz's own source language, echoed back
 * with attributes filled in -- against regexes pinned to two releases, with branches commented
 * "version 2.28" and "version 2.38". Graphviz has since changed its indentation and started
 * emitting decimal bounding boxes, so on any current install not one line matched, no node ever
 * moved, and the wizard reported success. See #30.
 *
 * <p>
 * Deliberately free of Eclipse, EMF and GEF: this turns text into numbers and can be tested
 * without a workbench or a Graphviz installation.
 *
 * @author Claude
 */
public class PlainLayout {

    /** Graphviz works in points; the plain format reports inches. */
    private static final double POINTS_PER_INCH = 72.0;

    /** A node's centre and size, in points. */
    public static class Node {
        public final String name;
        public final double x, y, width, height;

        Node(String name, double x, double y, double width, double height) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** An edge's control points, in points, tail first. */
    public static class Edge {
        public final String tail, head;
        public final double[] xs, ys;

        Edge(String tail, String head, double[] xs, double[] ys) {
            this.tail = tail;
            this.head = head;
            this.xs = xs;
            this.ys = ys;
        }

        public int size() {
            return xs.length;
        }
    }

    private final Map<String, Node> nodes = new HashMap<String, Node>();
    private final List<Edge> edges = new ArrayList<Edge>();
    private double width, height;
    private boolean sawGraph;

    /**
     * @param plain
     *            the whole of Graphviz's {@code -Tplain} output
     * @throws IOException
     *             if the text is unreadable or has no {@code graph} record
     */
    public PlainLayout(String plain) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(plain == null ? "" : plain)); //$NON-NLS-1$
        String line;
        while ((line = reader.readLine()) != null)
            parse(line);

        if (!sawGraph)
            throw new IOException("no 'graph' record: this is not Graphviz -Tplain output"); //$NON-NLS-1$
    }

    private void parse(String line) {
        // A record can be continued with a trailing backslash, and names can be quoted. Neither
        // arises for the identifiers this exporter emits (UrnNode123, cluster_45), so splitting on
        // whitespace is exact here rather than merely convenient.
        String[] f = line.trim().split("\\s+"); //$NON-NLS-1$
        if (f.length == 0)
            return;

        try {
            if ("graph".equals(f[0]) && f.length >= 4) { //$NON-NLS-1$
                // graph SCALE WIDTH HEIGHT -- the scale is for rendering, not for coordinates,
                // which are already absolute. Only the extent is wanted, to flip the y axis.
                width = Double.parseDouble(f[2]) * POINTS_PER_INCH;
                height = Double.parseDouble(f[3]) * POINTS_PER_INCH;
                sawGraph = true;
            } else if ("node".equals(f[0]) && f.length >= 6) { //$NON-NLS-1$
                nodes.put(f[1], new Node(f[1], inches(f[2]), inches(f[3]), inches(f[4]), inches(f[5])));
            } else if ("edge".equals(f[0]) && f.length >= 4) { //$NON-NLS-1$
                int count = Integer.parseInt(f[3]);
                if (count < 0 || f.length < 4 + 2 * count)
                    return;

                double[] xs = new double[count];
                double[] ys = new double[count];
                for (int i = 0; i < count; i++) {
                    xs[i] = inches(f[4 + 2 * i]);
                    ys[i] = inches(f[5 + 2 * i]);
                }
                edges.add(new Edge(f[1], f[2], xs, ys));
            }
        } catch (NumberFormatException ignored) {
            // A record we cannot read is skipped rather than failing the layout: one unusable
            // node should cost that node its position, not the whole map its layout.
        }
    }

    private static double inches(String s) {
        return Double.parseDouble(s) * POINTS_PER_INCH;
    }

    /** The drawing's width in points. */
    public double getWidth() {
        return width;
    }

    /** The drawing's height in points. Needed to flip Graphviz's bottom-left origin to SWT's top-left. */
    public double getHeight() {
        return height;
    }

    public Node getNode(String name) {
        return nodes.get(name);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public List<Edge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    /** The edge from tail to head, or null. */
    public Edge getEdge(String tail, String head) {
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            if (e.tail.equals(tail) && e.head.equals(head))
                return e;
        }
        return null;
    }
}
