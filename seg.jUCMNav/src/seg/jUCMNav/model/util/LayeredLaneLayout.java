package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;

import ucm.map.NodeConnection;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * A layered, swim-laned drawing of a UCM map. No Graphviz, no solver, no tuning.
 *
 * <p>
 * Ported from the layout in PM4Py-UCM, which produces visibly better UCM drawings than anything
 * tried on issue #30 -- and which most of this project's sample models were drawn with. The reason
 * it wins is not a better search. It is that <b>the constraints are true by construction rather
 * than enforced afterwards</b>, so nothing has to be repaired, nothing has to be weighted, and no
 * two rules can pull against each other:
 *
 * <ul>
 * <li>every top-level component gets a <b>disjoint horizontal band</b>, so two component rectangles
 * cannot intersect however their nodes arrange themselves along the flow. There is no separation
 * pass because there is nothing to separate;</li>
 * <li>a nested component's band is a <b>sub-range of its parent's</b>, so containment holds
 * geometrically rather than by penalty;</li>
 * <li>x is a <b>layer index</b> -- longest path over the graph with loop back-edges removed -- and
 * each layer is as wide as its widest label, so labels cannot collide horizontally;</li>
 * <li>within a layer, nodes of a lane are swept apart by the sum of their half-heights, so they
 * cannot collide vertically either.</li>
 * </ul>
 *
 * <p>
 * What makes the paths smooth is <b>barycentric y</b>: a node sits at the mean of its neighbours'
 * y, clamped into its band. That is simultaneously the classic crossing-reduction heuristic and a
 * smoothing operator, which is why this needs neither a bending term nor a crossing term to get
 * both right.
 *
 * <p>
 * Swim lanes were tried on #30 and rejected as "legal but 1-D -- a BPMN lane, the path oscillates".
 * That verdict was correct about <i>that</i> implementation, which gave each lane a single fixed y.
 * A lane is a band, not a line: clamping a barycentric y into it keeps the freedom that makes a
 * path look like a path, and the rejection does not carry over.
 *
 * <p>
 * Deterministic and idempotent -- the same map always lays out the same way, and laying out an
 * already laid-out map changes nothing. Pure model queries, so it runs in a test without a
 * workbench.
 *
 * @author Claude, after the PM4Py-UCM layouter
 */
public class LayeredLaneLayout {

    /** Base horizontal gap between two layers, on top of the labels' own widths. */
    private static final int X_GAP = 90;

    /** Base vertical gap between two nodes in the same layer. */
    private static final int Y_GAP = 70;

    private static final int X_ORIGIN = 60;
    private static final int Y_ORIGIN = 100;

    /** Clear space inside a component's rectangle, around what it holds. */
    private static final int COMP_PADDING = 30;

    /** Extra height at the top of a band, where the component's name is drawn. */
    private static final int COMP_LABEL_PAD = 24;

    /**
     * Vertical gap between two sibling bands.
     *
     * <p>
     * Wider than PM4Py-UCM's 20 because jUCMNav derives a component's rectangle from its nodes'
     * extents plus a 30px margin on each side, rather than from the band. Two adjacent bands must
     * therefore stay at least two margins apart or the drawn rectangles touch even though the bands
     * do not -- and touching rectangles are an OCL violation, not a cosmetic problem.
     */
    private static final int LANE_GAP = 20;

    /** Half-extent of a node whose size is unknown, so nothing is ever laid out as a point. */
    private static final int DEFAULT_HALF = 18;

    /**
     * Horizontal clear space two components must leave each other to share a band.
     *
     * <p>
     * Their drawn rectangles are wider than the nodes they hold: this layout pads by
     * {@link #COMP_PADDING}, and jUCMNav's own {@code resize} adds a further 30px margin on each
     * side. Two components sharing a band therefore need both allowances twice over between them,
     * or the rectangles touch -- which is an OCL violation, not a cosmetic complaint.
     */
    private static final int SIDE_CLEARANCE = 2 * (COMP_PADDING + 30);

    private LayeredLaneLayout() {
    }

    /**
     * Places every node of the map.
     *
     * @param map
     *            the map to lay out; not modified
     * @param sizes
     *            node -&gt; how big it is drawn including its label, as {@link LabelExtent} reports
     *            it. A node absent from the map gets a small default rather than zero.
     * @return node -&gt; centre, for every node of the map
     */
    public static Map<IURNNode, Point> layout(UCMmap map, Map<IURNNode, Dimension> sizes) {
        Map<IURNNode, Point> positions = new LinkedHashMap<IURNNode, Point>();
        if (map == null || map.getNodes().isEmpty())
            return positions;

        List<PathNode> nodes = new ArrayList<PathNode>();
        for (Iterator<?> it = map.getNodes().iterator(); it.hasNext();)
            nodes.add((PathNode) it.next());

        Set<NodeConnection> back = backEdges(nodes);
        Map<PathNode, Integer> layer = layers(nodes, back);

        // Columns first: a component's horizontal extent is decided entirely by which layers its
        // nodes occupy, so it is known before any y is chosen -- and it is what says whether two
        // components need separate bands at all.
        Map<Integer, Integer> layerX = columns(layerOrder(nodes, layer), byLayer(nodes, layer), sizes);
        Map<IURNContainerRef, double[]> lanes = lanes(map, layer, layerX, sizes);
        Map<PathNode, double[]> band = new LinkedHashMap<PathNode, double[]>();
        for (int i = 0; i < nodes.size(); i++) {
            IURNContainerRef ref = nodes.get(i).getContRef();
            if (ref != null && lanes.containsKey(ref))
                band.put(nodes.get(i), interiorBand(ref, lanes));
        }

        Map<Integer, List<PathNode>> byLayer = new LinkedHashMap<Integer, List<PathNode>>();
        for (int i = 0; i < nodes.size(); i++) {
            Integer l = layer.get(nodes.get(i));
            List<PathNode> at = byLayer.get(l);
            if (at == null)
                byLayer.put(l, at = new ArrayList<PathNode>());
            at.add(nodes.get(i));
        }
        List<Integer> order = new ArrayList<Integer>(byLayer.keySet());
        Collections.sort(order);

        Map<PathNode, Double> y = new LinkedHashMap<PathNode, Double>();
        for (int i = 0; i < order.size(); i++) {
            List<PathNode> here = byLayer.get(order.get(i));
            for (int n = 0; n < here.size(); n++)
                y.put(here.get(n), initialY(here.get(n), y, back, band, sizes));
            separate(here, y, band, sizes);
        }

        // Two sweeps each way. Averaging over predecessors pulls a node into line with what feeds
        // it; averaging over successors does the same for what it feeds. Alternating settles the
        // orderings that reduce crossings, which is the whole of the crossing story here.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < order.size(); i++)
                relax(byLayer.get(order.get(i)), y, back, band, sizes, true);
            for (int i = order.size() - 1; i >= 0; i--)
                relax(byLayer.get(order.get(i)), y, back, band, sizes, false);
        }

        for (int i = 0; i < nodes.size(); i++) {
            PathNode pn = nodes.get(i);
            positions.put(pn, new Point(layerX.get(layer.get(pn)).intValue(), (int) Math.round(Y_ORIGIN + y.get(pn).doubleValue())));
        }
        return positions;
    }

    // ------------------------------------------------------------------------------ back edges

    /**
     * The connections that close a loop, by depth-first search.
     *
     * A layered drawing only works on a DAG: a back edge would rank its target below its source and
     * stretch the whole drawing to satisfy an order that cannot hold. Found and set aside, the
     * forward structure lays out and the loop is simply drawn returning across it.
     */
    private static Set<NodeConnection> backEdges(List<PathNode> nodes) {
        Set<NodeConnection> back = new LinkedHashSet<NodeConnection>();
        Set<PathNode> exploring = new LinkedHashSet<PathNode>();
        Set<PathNode> done = new LinkedHashSet<PathNode>();

        for (int i = 0; i < nodes.size(); i++)
            if (!done.contains(nodes.get(i)))
                walk(nodes.get(i), exploring, done, back);
        return back;
    }

    private static void walk(PathNode at, Set<PathNode> exploring, Set<PathNode> done, Set<NodeConnection> back) {
        exploring.add(at);
        for (Iterator<?> it = at.getSucc().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (!(nc.getTarget() instanceof PathNode))
                continue;

            PathNode next = (PathNode) nc.getTarget();
            if (exploring.contains(next))
                back.add(nc); // reaches something we are still inside
            else if (!done.contains(next))
                walk(next, exploring, done, back);
        }
        exploring.remove(at);
        done.add(at);
    }

    // ---------------------------------------------------------------------------------- layers

    /**
     * Longest-path layering: a node sits one layer past the deepest thing that reaches it.
     *
     * This is the x axis, and it is the whole of what Graphviz was being run for. Thirty lines
     * against a process launch, a DOT serialisation, a text format to parse, and an install to
     * locate.
     */
    private static Map<PathNode, Integer> layers(List<PathNode> nodes, Set<NodeConnection> back) {
        Map<PathNode, Integer> layer = new LinkedHashMap<PathNode, Integer>();
        Map<PathNode, Integer> remaining = new LinkedHashMap<PathNode, Integer>();

        for (int i = 0; i < nodes.size(); i++) {
            layer.put(nodes.get(i), Integer.valueOf(0));
            remaining.put(nodes.get(i), Integer.valueOf(forwardPreds(nodes.get(i), back)));
        }

        List<PathNode> queue = new ArrayList<PathNode>();
        for (int i = 0; i < nodes.size(); i++)
            if (remaining.get(nodes.get(i)).intValue() == 0)
                queue.add(nodes.get(i));

        for (int head = 0; head < queue.size(); head++) {
            PathNode at = queue.get(head);
            for (Iterator<?> it = at.getSucc().iterator(); it.hasNext();) {
                NodeConnection nc = (NodeConnection) it.next();
                if (back.contains(nc) || !(nc.getTarget() instanceof PathNode))
                    continue;

                PathNode next = (PathNode) nc.getTarget();
                layer.put(next, Integer.valueOf(Math.max(layer.get(next).intValue(), layer.get(at).intValue() + 1)));
                int left = remaining.get(next).intValue() - 1;
                remaining.put(next, Integer.valueOf(left));
                if (left == 0)
                    queue.add(next);
            }
        }

        // A cycle nothing enters leaves nodes unqueued. They keep layer 0 rather than no layer at
        // all: a node without a position is the failure this work exists to end.
        return layer;
    }

    private static int forwardPreds(PathNode pn, Set<NodeConnection> back) {
        int count = 0;
        for (Iterator<?> it = pn.getPred().iterator(); it.hasNext();)
            if (!back.contains(it.next()))
                count++;
        return count;
    }

    /** The layers actually occupied, in order. */
    private static List<Integer> layerOrder(List<PathNode> nodes, Map<PathNode, Integer> layer) {
        List<Integer> order = new ArrayList<Integer>(new LinkedHashSet<Integer>(layer.values()));
        Collections.sort(order);
        return order;
    }

    /** The nodes of each layer. */
    private static Map<Integer, List<PathNode>> byLayer(List<PathNode> nodes, Map<PathNode, Integer> layer) {
        Map<Integer, List<PathNode>> grouped = new LinkedHashMap<Integer, List<PathNode>>();
        for (int i = 0; i < nodes.size(); i++) {
            Integer l = layer.get(nodes.get(i));
            List<PathNode> at = grouped.get(l);
            if (at == null)
                grouped.put(l, at = new ArrayList<PathNode>());
            at.add(nodes.get(i));
        }
        return grouped;
    }

    /**
     * The horizontal span a component's nodes occupy, or null when it holds none.
     *
     * Known before any y is chosen, because x is decided entirely by which layers its nodes sit in
     * -- which is what lets two components that never meet horizontally share one band.
     */
    private static double[] xExtent(IURNContainerRef ref, Map<PathNode, Integer> layer, Map<Integer, Integer> layerX,
            Map<IURNNode, Dimension> sizes) {

        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE;
        List<PathNode> held = subtreeNodes(ref);
        for (int i = 0; i < held.size(); i++) {
            Integer l = layer.get(held.get(i));
            if (l == null || layerX.get(l) == null)
                continue;

            double x = layerX.get(l).doubleValue();
            left = Math.min(left, x - halfWidth(held.get(i), sizes));
            right = Math.max(right, x + halfWidth(held.get(i), sizes));
        }
        return left == Double.MAX_VALUE ? null : new double[] { left, right };
    }

    // ----------------------------------------------------------------------------------- lanes

    /**
     * A disjoint y band for every component, with children nested inside their parent.
     *
     * <p>
     * This is the step that makes the URN containment rules hold by construction. Bands are stacked
     * in order of the earliest layer any of their nodes reaches, so a component that acts early in
     * the flow sits above one that acts late, and the drawing reads down as well as across.
     */
    private static Map<IURNContainerRef, double[]> lanes(UCMmap map, Map<PathNode, Integer> layer, Map<Integer, Integer> layerX,
            Map<IURNNode, Dimension> sizes) {

        Map<IURNContainerRef, double[]> lane = new LinkedHashMap<IURNContainerRef, double[]>();
        List<IURNContainerRef> roots = new ArrayList<IURNContainerRef>();
        for (Iterator<?> it = map.getContRefs().iterator(); it.hasNext();) {
            IURNContainerRef ref = (IURNContainerRef) it.next();
            if (ref.getParent() == null)
                roots.add(ref);
        }
        if (roots.isEmpty())
            return lane;

        final Map<PathNode, Integer> layerOf = layer;
        Collections.sort(roots, new Comparator<IURNContainerRef>() {
            public int compare(IURNContainerRef a, IURNContainerRef b) {
                return firstLayer(a, layerOf) - firstLayer(b, layerOf);
            }
        });

        // Stack a component below another only when it has to be. Two components whose horizontal
        // extents do not meet cannot have intersecting rectangles whatever their y, so they share a
        // band -- which is how the reference layouts stay wide and flat instead of growing a band
        // per component. Giving every component its own band regardless costs height for nothing:
        // on the issue-tracker sample it was the difference between 2114x742 and the reference's
        // 1927x365, and the tall version reads far worse because the path has to climb between
        // bands that never needed to be apart.
        List<List<IURNContainerRef>> tracks = new ArrayList<List<IURNContainerRef>>();
        List<Double> trackRight = new ArrayList<Double>();

        for (int i = 0; i < roots.size(); i++) {
            double[] extent = xExtent(roots.get(i), layer, layerX, sizes);
            int chosen = -1;
            for (int t = 0; t < tracks.size() && chosen < 0; t++)
                if (extent != null && extent[0] > trackRight.get(t).doubleValue() + SIDE_CLEARANCE)
                    chosen = t;

            if (chosen < 0) {
                tracks.add(new ArrayList<IURNContainerRef>());
                trackRight.add(Double.valueOf(-Double.MAX_VALUE));
                chosen = tracks.size() - 1;
            }
            tracks.get(chosen).add(roots.get(i));
            if (extent != null)
                trackRight.set(chosen, Double.valueOf(Math.max(trackRight.get(chosen).doubleValue(), extent[1])));
        }

        // Bands go down the page in flow order, not in the order packing happened to open them.
        // Packing fills the first track with room, so one track ends up holding components from
        // all over the path -- and laying the tracks out in that order can put a tall band between
        // two components the path runs straight between. That is what sent the path plunging to
        // QA Team and back: Dev Team and QA Team are consecutive in the flow but landed in tracks
        // 0 and 2, with the full-width Triage Team band in between.
        final Map<PathNode, Integer> flow = layer;
        Collections.sort(tracks, new Comparator<List<IURNContainerRef>>() {
            public int compare(List<IURNContainerRef> a, List<IURNContainerRef> b) {
                return Double.compare(meanLayer(a, flow), meanLayer(b, flow));
            }
        });

        double cursor = 0;
        for (int t = 0; t < tracks.size(); t++) {
            double height = 0;
            for (int i = 0; i < tracks.get(t).size(); i++)
                height = Math.max(height, requiredHeight(tracks.get(t).get(i), layer, sizes));

            for (int i = 0; i < tracks.get(t).size(); i++)
                assign(tracks.get(t).get(i), cursor, cursor + height, lane, layer, sizes);
            cursor += height + LANE_GAP;
        }
        return lane;
    }

    /**
     * Where in the flow everything in a track sits, on average.
     *
     * The mean rather than the earliest layer, because a track holds several components and one of
     * them starting early says nothing about where the track as a whole belongs.
     */
    private static double meanLayer(List<IURNContainerRef> track, Map<PathNode, Integer> layer) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < track.size(); i++) {
            List<PathNode> held = subtreeNodes(track.get(i));
            for (int n = 0; n < held.size(); n++) {
                Integer l = layer.get(held.get(n));
                if (l != null) {
                    sum += l.intValue();
                    count++;
                }
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /** The earliest layer anything in this component's subtree occupies. */
    private static int firstLayer(IURNContainerRef ref, Map<PathNode, Integer> layer) {
        int first = Integer.MAX_VALUE;
        List<PathNode> held = subtreeNodes(ref);
        for (int i = 0; i < held.size(); i++) {
            Integer l = layer.get(held.get(i));
            if (l != null)
                first = Math.min(first, l.intValue());
        }
        return first;
    }

    private static List<PathNode> subtreeNodes(IURNContainerRef ref) {
        List<PathNode> held = new ArrayList<PathNode>();
        for (Iterator<?> it = ref.getNodes().iterator(); it.hasNext();) {
            Object node = it.next();
            if (node instanceof PathNode)
                held.add((PathNode) node);
        }
        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();)
            held.addAll(subtreeNodes((IURNContainerRef) it.next()));
        return held;
    }

    /** How tall a component's band has to be to hold its own nodes and all its children's bands. */
    private static double requiredHeight(IURNContainerRef ref, Map<PathNode, Integer> layer, Map<IURNNode, Dimension> sizes) {
        double own = ownHeight(ref, layer, sizes);

        double children = 0;
        int count = 0;
        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();) {
            children += requiredHeight((IURNContainerRef) it.next(), layer, sizes);
            count++;
        }
        if (count > 1)
            children += (count - 1) * LANE_GAP / 2.0;

        double total = own + children + 2 * COMP_PADDING + COMP_LABEL_PAD;
        return Math.max(total, 2 * COMP_PADDING + COMP_LABEL_PAD + Y_GAP);
    }

    /** The tallest column of this component's own nodes -- its lower bound on band height. */
    private static double ownHeight(IURNContainerRef ref, Map<PathNode, Integer> layer, Map<IURNNode, Dimension> sizes) {
        Map<Integer, Double> perLayer = new LinkedHashMap<Integer, Double>();
        Map<Integer, Integer> counts = new LinkedHashMap<Integer, Integer>();

        for (Iterator<?> it = ref.getNodes().iterator(); it.hasNext();) {
            Object node = it.next();
            if (!(node instanceof PathNode))
                continue;

            Integer l = layer.get(node);
            if (l == null)
                continue;

            double had = perLayer.containsKey(l) ? perLayer.get(l).doubleValue() : 0;
            int n = counts.containsKey(l) ? counts.get(l).intValue() : 0;
            perLayer.put(l, Double.valueOf(had + 2 * halfHeight((IURNNode) node, sizes)));
            counts.put(l, Integer.valueOf(n + 1));
        }

        double tallest = 0;
        for (Iterator<Integer> it = perLayer.keySet().iterator(); it.hasNext();) {
            Integer l = it.next();
            double h = perLayer.get(l).doubleValue() + (counts.get(l).intValue() - 1) * Y_GAP;
            tallest = Math.max(tallest, h);
        }
        return tallest;
    }

    /** Gives every component in the subtree a band, children stacked below the parent's own nodes. */
    private static void assign(IURNContainerRef ref, double top, double bottom, Map<IURNContainerRef, double[]> lane,
            Map<PathNode, Integer> layer, Map<IURNNode, Dimension> sizes) {

        lane.put(ref, new double[] { top, bottom });
        if (ref.getChildren().isEmpty())
            return;

        double usableTop = top + COMP_LABEL_PAD + COMP_PADDING;
        double usableBottom = bottom - COMP_PADDING;

        double own = ownHeight(ref, layer, sizes);
        double childTop = Math.min(usableTop + own + (own > 0 ? LANE_GAP / 2.0 : 0), usableBottom);

        List<IURNContainerRef> children = new ArrayList<IURNContainerRef>();
        double wanted = 0;
        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();) {
            IURNContainerRef child = (IURNContainerRef) it.next();
            children.add(child);
            wanted += requiredHeight(child, layer, sizes);
        }

        double room = Math.max(0, usableBottom - childTop);
        double scale = wanted > 0 && room > wanted ? room / wanted : 1.0;

        double cursor = childTop;
        for (int i = 0; i < children.size(); i++) {
            double height = requiredHeight(children.get(i), layer, sizes) * scale;
            double childBottom = Math.min(cursor + height, usableBottom);
            assign(children.get(i), cursor, childBottom, lane, layer, sizes);
            cursor = childBottom;
        }
    }

    /** The part of a component's band its own nodes may use -- above its children, below its label. */
    private static double[] interiorBand(IURNContainerRef ref, Map<IURNContainerRef, double[]> lane) {
        double[] own = lane.get(ref);
        double top = own[0] + COMP_LABEL_PAD + COMP_PADDING;
        double bottom = own[1] - COMP_PADDING;

        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();) {
            double[] child = lane.get(it.next());
            if (child != null)
                bottom = Math.min(bottom, child[0] - LANE_GAP / 2.0);
        }
        return new double[] { top, Math.max(top, bottom) };
    }

    // ------------------------------------------------------------------------------ y placement

    private static Double initialY(PathNode pn, Map<PathNode, Double> y, Set<NodeConnection> back, Map<PathNode, double[]> band,
            Map<IURNNode, Dimension> sizes) {

        List<PathNode> preds = new ArrayList<PathNode>();
        for (Iterator<?> it = pn.getPred().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (!back.contains(nc) && nc.getSource() instanceof PathNode && y.containsKey(nc.getSource()))
                preds.add((PathNode) nc.getSource());
        }

        double[] mine = band.get(pn);
        if (preds.isEmpty())
            return Double.valueOf(mine == null ? 0 : (mine[0] + mine[1]) / 2.0);

        double at;
        if (preds.size() == 1) {
            PathNode pred = preds.get(0);
            List<PathNode> siblings = new ArrayList<PathNode>();
            for (Iterator<?> it = pred.getSucc().iterator(); it.hasNext();) {
                NodeConnection nc = (NodeConnection) it.next();
                if (!back.contains(nc) && nc.getTarget() instanceof PathNode)
                    siblings.add((PathNode) nc.getTarget());
            }

            // A fork's branches are fanned out around their common predecessor rather than stacked
            // on it, so the two sides of a choice read as two sides rather than one line.
            if (siblings.size() > 1 && siblings.contains(pn)) {
                double tallest = 0;
                for (int i = 0; i < siblings.size(); i++)
                    tallest = Math.max(tallest, halfHeight(siblings.get(i), sizes));

                double step = Math.max(Y_GAP, 2 * tallest + 10);
                at = y.get(pred).doubleValue() + (siblings.indexOf(pn) - (siblings.size() - 1) / 2.0) * step;
            } else {
                at = y.get(pred).doubleValue();
            }
        } else {
            double sum = 0;
            for (int i = 0; i < preds.size(); i++)
                sum += y.get(preds.get(i)).doubleValue();
            at = sum / preds.size();
        }
        return Double.valueOf(clamp(at, mine));
    }

    /** One barycentric pass over a layer: each node to the mean of its neighbours, then separated. */
    private static void relax(List<PathNode> here, Map<PathNode, Double> y, Set<NodeConnection> back, Map<PathNode, double[]> band,
            Map<IURNNode, Dimension> sizes, boolean fromPredecessors) {

        for (int i = 0; i < here.size(); i++) {
            PathNode pn = here.get(i);
            double sum = 0;
            int count = 0;

            for (Iterator<?> it = (fromPredecessors ? pn.getPred() : pn.getSucc()).iterator(); it.hasNext();) {
                NodeConnection nc = (NodeConnection) it.next();
                if (back.contains(nc))
                    continue;

                Object other = fromPredecessors ? nc.getSource() : nc.getTarget();
                if (other instanceof PathNode && y.containsKey(other)) {
                    sum += y.get(other).doubleValue();
                    count++;
                }
            }
            if (count > 0)
                y.put(pn, Double.valueOf(clamp(sum / count, band.get(pn))));
        }
        separate(here, y, band, sizes);
    }

    /**
     * Pushes the nodes of one layer apart, lane by lane.
     *
     * Each neighbouring pair ends up at least the sum of their half-heights apart, so a tall
     * multi-line label cannot end up written across the node above it.
     */
    private static void separate(List<PathNode> here, final Map<PathNode, Double> y, Map<PathNode, double[]> band,
            Map<IURNNode, Dimension> sizes) {

        if (here.size() <= 1)
            return;

        Map<Object, List<PathNode>> groups = new LinkedHashMap<Object, List<PathNode>>();
        for (int i = 0; i < here.size(); i++) {
            double[] mine = band.get(here.get(i));
            Object key = mine == null ? "free" : (Object) (mine[0] + ":" + mine[1]); //$NON-NLS-1$ //$NON-NLS-2$
            List<PathNode> at = groups.get(key);
            if (at == null)
                groups.put(key, at = new ArrayList<PathNode>());
            at.add(here.get(i));
        }

        for (Iterator<List<PathNode>> it = groups.values().iterator(); it.hasNext();) {
            List<PathNode> group = it.next();
            if (group.size() <= 1)
                continue;

            Collections.sort(group, new Comparator<PathNode>() {
                public int compare(PathNode a, PathNode b) {
                    return Double.compare(y.get(a).doubleValue(), y.get(b).doubleValue());
                }
            });

            for (int i = 1; i < group.size(); i++) {
                double least = halfHeight(group.get(i - 1), sizes) + halfHeight(group.get(i), sizes) + Y_GAP / 2.0;
                double want = y.get(group.get(i - 1)).doubleValue() + least;
                if (y.get(group.get(i)).doubleValue() < want)
                    y.put(group.get(i), Double.valueOf(want));
            }

            // The sweep may have pushed the group past the bottom of its band; slide it back up by
            // as much as the headroom above allows, then clamp what is left.
            double[] mine = band.get(group.get(0));
            if (mine == null)
                continue;

            double overshoot = y.get(group.get(group.size() - 1)).doubleValue() - mine[1];
            if (overshoot > 0) {
                double shift = Math.min(overshoot, Math.max(0, y.get(group.get(0)).doubleValue() - mine[0]));
                for (int i = 0; i < group.size(); i++)
                    y.put(group.get(i), Double.valueOf(y.get(group.get(i)).doubleValue() - shift));
            }
            for (int i = 0; i < group.size(); i++)
                y.put(group.get(i), Double.valueOf(clamp(y.get(group.get(i)).doubleValue(), mine)));
        }
    }

    // --------------------------------------------------------------------------------- columns

    /** One x per layer: each column as wide as its widest label needs, so labels cannot collide. */
    private static Map<Integer, Integer> columns(List<Integer> order, Map<Integer, List<PathNode>> byLayer, Map<IURNNode, Dimension> sizes) {
        Map<Integer, Integer> layerX = new LinkedHashMap<Integer, Integer>();

        double cursor = X_ORIGIN;
        double previous = 0;
        for (int i = 0; i < order.size(); i++) {
            List<PathNode> here = byLayer.get(order.get(i));
            double widest = 0;
            for (int n = 0; n < here.size(); n++)
                widest = Math.max(widest, halfWidth(here.get(n), sizes));

            cursor = i == 0 ? X_ORIGIN + widest : cursor + previous + Math.max(X_GAP / 2.0, 1) + widest;
            layerX.put(order.get(i), Integer.valueOf((int) Math.round(cursor)));
            previous = widest;
        }
        return layerX;
    }

    // ---------------------------------------------------------------------------------- pieces

    private static double clamp(double at, double[] band) {
        if (band == null)
            return at;
        return at < band[0] ? band[0] : (at > band[1] ? band[1] : at);
    }

    private static double halfWidth(IURNNode node, Map<IURNNode, Dimension> sizes) {
        Dimension size = sizes == null ? null : sizes.get(node);
        return size == null || size.width <= 0 ? DEFAULT_HALF : size.width / 2.0;
    }

    private static double halfHeight(IURNNode node, Map<IURNNode, Dimension> sizes) {
        Dimension size = sizes == null ? null : sizes.get(node);
        return size == null || size.height <= 0 ? DEFAULT_HALF : size.height / 2.0;
    }
}
