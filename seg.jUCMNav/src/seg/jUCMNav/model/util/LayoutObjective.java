package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;

import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * What makes one drawing of a map better than another, as a number.
 *
 * <p>
 * Every auto-layout attempt so far has been judged by looking at a PNG, and the record shows what
 * that is worth: four approaches were tried, two of them shipped, and the reason each was eventually
 * abandoned -- components inflating into near-empty rectangles, paths plunging between them -- was
 * visible in the very first render of each and argued about for days anyway. Opinions about
 * screenshots do not compose. Numbers do.
 *
 * <p>
 * This computes the objective agreed on issue #30, over positions the layout has already chosen:
 *
 * <pre>
 *   w1 * bending    turn angle^2 at each node along each chain
 * + w2 * sprawl     component box area against the area of what it holds
 * + w3 * overlap    pairwise intersection of element+label boxes
 * + w4 * spread     how far apart consecutive nodes sit
 * </pre>
 *
 * <p>
 * Two things it deliberately is not. It is <b>not</b> a legality check -- {@code
 * AutoLayoutLegalityTest} runs jUCMNav's own OCL rules for that, and those are the tool's own
 * definition of a drawing that is allowed at all. This scores drawings that are already legal. And
 * it has no notion of <b>rank</b>: URN has none, and importing one is what every rejected approach
 * did wrong.
 *
 * <p>
 * Each term is reported separately and normalised to something dimensionless and readable, because
 * the immediately useful output is not the scalar but the breakdown -- a layout that scores badly
 * should say <i>which</i> of the four it lost on. The scalar exists so a solver has something to
 * descend, and its weights are the part still to be calibrated: no attempt is made here to guess
 * them, since the calibration data is exactly what this class is being built to collect.
 *
 * <p>
 * Pure geometry over a supplied map of positions -- no workbench, no editor, no Graphviz -- so it
 * can be computed inside a test, inside the render sweep, and eventually inside a solver's inner
 * loop. The three measures are exposed separately from the model walk that assembles them, so the
 * arithmetic can be tested on rectangles and polylines directly.
 *
 * @author Claude
 */
public class LayoutObjective {

    /**
     * The gap a well-drawn map leaves between consecutive nodes on a path, in pixels.
     *
     * <p>
     * Only a unit: the spread term divides by it so that 1.0 reads as "as tight as a good layout draws it" and 3.0 as "three times looser than it needs to be". Comparisons are between
     * layouts of the same map, so the constant cancels; it is here to make the number mean
     * something on its own rather than to be exact.
     */
    public static final double NATURAL_SPACING = 60.0;

    /** The margin a component's rectangle is drawn with around its contents, as the wizard uses. */
    public static final int DEFAULT_COMPONENT_MARGIN = 30;

    /** Segments shorter than this carry no reliable direction, so no turn is measured across them. */
    private static final double MIN_SEGMENT = 1e-6;

    // ----------------------------------------------------------------------------- the score

    /** How the four terms came out, raw and normalised. */
    public static class Score {

        /** Mean squared turn angle, in radians squared. 0 is a perfectly straight path. */
        public final double bending;

        /**
         * Component box area over the area of what it holds. 1.0 is a box with no slack.
         *
         * <p>
         * 1.0 is a floor, not a target: a component legitimately contains whitespace, since its
         * nodes are strung along a path rather than packed. The PM4Py-UCM issue-tracker sample
         * measures <b>15.05</b>, and driving it below that would mean components tighter than PM4Py-UCM draws them. Judge this against the PM4Py-UCM baseline, never against 1.
         *
         * <p>
         * Note this is a ratio where issue #30 states the term as raw area. Raw area is not
         * comparable between models, nor between two drawings at different scales, which defeats
         * the purpose of scoring; {@link #totalComponentArea} still reports the literal form.
         */
        public final double sprawl;

        /** Overlapping box area as a fraction of total box area. 0 is nothing touching. */
        public final double overlap;

        /** Mean gap between consecutive nodes, in units of {@link #NATURAL_SPACING}. */
        public final double spread;

        /**
         * Path crossings as a fraction of the path segments drawn. 0 is a planar drawing.
         *
         * <p>
         * The fifth term, and not one of the four on issue #30 -- see
         * {@code docs/auto-layout-objective.md} for why it had to be added. Briefly: nothing in
         * the other four charges a drawing for one path crossing another, so a solver given the
         * four alone spends its freedom on crossings and scores beautifully while producing a
         * tangle. Graphviz had been supplying crossing minimisation silently, and the solver that
         * replaced it inherited a responsibility nobody had written down.
         */
        public final double crossingRate;

        /** How many times the drawn paths properly cross. The count behind {@link #crossingRate}. */
        public final int crossings;

        /** Root-mean-square turn in degrees -- {@link #bending}, in a unit people read. */
        public final double rmsTurnDegrees;

        /** Sum of squared turn angles, radians squared, unnormalised. */
        public final double totalBending;

        /** Total area of all component boxes, square pixels. */
        public final double totalComponentArea;

        /** Total area those components' contents actually occupy, square pixels. */
        public final double totalContentArea;

        /** Total pairwise overlapping area, square pixels. Pairs overlapping thrice count thrice. */
        public final double totalOverlapArea;

        /** Total length of every chain polyline, pixels. */
        public final double totalLength;

        /** How many vertices a turn was measured at. */
        public final int turns;

        /** How many components had a box. */
        public final int components;

        /** How many node boxes went into the overlap term. */
        public final int boxes;

        /** How many node-to-node segments went into the spread term. */
        public final int segments;

        Score(double bending, double sprawl, double overlap, double spread, double crossingRate, int crossings, double totalBending,
                double totalComponentArea, double totalContentArea, double totalOverlapArea, double totalLength, int turns, int components,
                int boxes, int segments) {
            this.bending = bending;
            this.sprawl = sprawl;
            this.overlap = overlap;
            this.spread = spread;
            this.crossingRate = crossingRate;
            this.crossings = crossings;
            this.rmsTurnDegrees = Math.toDegrees(Math.sqrt(bending));
            this.totalBending = totalBending;
            this.totalComponentArea = totalComponentArea;
            this.totalContentArea = totalContentArea;
            this.totalOverlapArea = totalOverlapArea;
            this.totalLength = totalLength;
            this.turns = turns;
            this.components = components;
            this.boxes = boxes;
            this.segments = segments;
        }

        /**
         * The single number, under the given weights.
         *
         * <p>
         * Note that two of the terms have a floor above zero -- a component box cannot be smaller
         * than its contents, and nodes cannot sit on top of each other -- so the total does not
         * approach 0 for a perfect drawing. It is a quantity to compare, not to interpret.
         */
        public double total(Weights w) {
            return w.bending * bending + w.sprawl * sprawl + w.overlap * overlap + w.spread * spread + w.crossings * crossingRate;
        }

        /** The total under {@link Weights#DEFAULT}. */
        public double total() {
            return total(Weights.DEFAULT);
        }

        /** One line, for a test or a render sweep to print next to the model's name. */
        public String toString() {
            return String.format(java.util.Locale.US, "bend %.4f (rms %.1f deg over %d) | sprawl %.2f (%.0f/%.0f over %d) " //$NON-NLS-1$
                    + "| overlap %.4f (%.0f over %d) | spread %.2f (%.0f over %d) | cross %.4f (%d) | total %.3f", //$NON-NLS-1$
                    Double.valueOf(bending), Double.valueOf(rmsTurnDegrees), Integer.valueOf(turns), Double.valueOf(sprawl),
                    Double.valueOf(totalComponentArea), Double.valueOf(totalContentArea), Integer.valueOf(components),
                    Double.valueOf(overlap), Double.valueOf(totalOverlapArea), Integer.valueOf(boxes), Double.valueOf(spread),
                    Double.valueOf(totalLength), Integer.valueOf(segments), Double.valueOf(crossingRate), Integer.valueOf(crossings),
                    Double.valueOf(total()));
        }
    }

    /**
     * The four weights.
     *
     * <p>
     * All 1 until there is data to set them from, and <b>unit weights are not a neutral choice</b>
     * -- the first measurement says so. On the issue-tracker sample, PM4Py-UCM's layout against the same
     * nodes scattered over the same rectangle:
     *
     * <pre>
     *            PM4Py-UCM   scattered   ratio
     *   bending      0.80        5.70      7.1x
     *   sprawl      15.05       26.27      1.7x
     *   overlap      0.005       0.063    12.5x
     *   spread       2.12        9.72      4.6x
     *   total       17.98       41.76
     * </pre>
     *
     * Sprawl is the <i>least</i> discriminating of the four and the largest in magnitude, so at
     * unit weights it contributes about 85% of the total and the scalar is very nearly a report on
     * component boxes alone. Anything tuned against {@link #DEFAULT} today is tuned against sprawl.
     * That is a calibration to do with data, not a bug to patch by guessing a divisor -- but it has
     * to be known, because the whole point of a scalar is that people stop looking at the parts.
     */
    public static class Weights {

        /**
         * Each term weighted by the inverse of its own good-to-bad spread.
         *
         * <p>
         * So that a term contributes to the total in proportion to how much it actually
         * distinguishes drawings, rather than to how large its raw numbers happen to be. The
         * calibration pair is the PM4Py-UCM issue-tracker sample against the same nodes scattered
         * over the same rectangle -- the two ends of the range this measure has to cover:
         *
         * <pre>
         *              PM4Py-UCM   scattered   spread   weight = 1/spread
         *   bending        0.80        5.70       4.90        0.204
         *   sprawl        15.05       26.27      11.22        0.089
         *   overlap        0.0050      0.0627     0.0577     17.33
         *   spread         2.12        9.72       7.60        0.132
         *   crossings      0.0333      2.4333     2.400       0.417
         * </pre>
         *
         * <p>
         * Every figure above is measured, including the crossing row -- 1 crossing in the
         * PM4Py-UCM map against 73 in the scattered one. An earlier draft of this table carried a
         * guessed crossing spread that was off by a factor of ten, which would have made the term
         * ten times too strong. The whole point of the exercise recorded in
         * {@code docs/auto-layout-objective.md} is that guessed numbers in an objective do not stay
         * harmless.
         *
         * <p>
         * All 1 was not a neutral choice, which is why this exists: at unit weights sprawl was
         * about 85% of the total while discriminating least of the five, so the scalar was very
         * nearly a report on component boxes alone and anything tuned against it was tuned against
         * those. Under this calibration each term moves the total by roughly 1.0 across the full
         * range of drawing quality, and the five are commensurable.
         *
         * <p>
         * Still a calibration and not a truth: it says each term should matter equally, which is a
         * choice nobody has justified from how these diagrams are actually read. It is a defensible
         * starting point rather than a finished answer.
         */
        public static final Weights DEFAULT = new Weights(0.204, 0.089, 17.33, 0.132, 0.417);

        /** All terms equal, which is what the objective on issue #30 literally says. */
        public static final Weights UNIT = new Weights(1, 1, 1, 1, 1);

        public final double bending, sprawl, overlap, spread, crossings;

        public Weights(double bending, double sprawl, double overlap, double spread, double crossings) {
            this.bending = bending;
            this.sprawl = sprawl;
            this.overlap = overlap;
            this.spread = spread;
            this.crossings = crossings;
        }
    }

    // --------------------------------------------------------------------------- the model walk

    /**
     * Scores a placement of a decomposed UCM map.
     *
     * @param decomposition
     *            the map's junctions and chains; may be null, in which case there are no paths to
     *            measure and only the box terms are computed
     * @param positions
     *            node -&gt; centre, as the layout chose them
     * @param sizes
     *            node -&gt; how big it is drawn <i>including its label</i>, as {@link LabelExtent}
     *            reports it; a node absent from this map is treated as a point
     */
    public static Score evaluate(UcmPathDecomposition decomposition, Map<IURNNode, Point> positions, Map<IURNNode, Dimension> sizes) {
        return evaluate(decomposition, positions, sizes, DEFAULT_COMPONENT_MARGIN);
    }

    /** As above, with the component margin stated. */
    public static Score evaluate(UcmPathDecomposition decomposition, Map<IURNNode, Point> positions, Map<IURNNode, Dimension> sizes,
            int margin) {

        if (positions == null || positions.isEmpty())
            return new Score(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        List<PointList> routes = routesOf(decomposition, positions);

        List<Rectangle> componentBoxes = new ArrayList<Rectangle>();
        List<Double> contentAreas = new ArrayList<Double>();
        componentBoxesOf(positions, sizes, margin, componentBoxes, contentAreas);

        List<Rectangle> nodeBoxes = new ArrayList<Rectangle>();
        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            nodeBoxes.add(boxOf(node, positions, sizes));
        }

        return combine(routes, componentBoxes, contentAreas, nodeBoxes);
    }

    /**
     * The score for already-assembled geometry.
     *
     * <p>
     * The seam the tests use: everything above turns a model into these four lists, and everything
     * below is arithmetic on rectangles and polylines that can be checked by hand.
     */
    public static Score combine(List<PointList> routes, List<Rectangle> componentBoxes, List<Double> contentAreas, List<Rectangle> nodeBoxes) {

        double totalBending = 0;
        int turns = 0;
        double totalLength = 0;
        int segments = 0;

        for (int i = 0; routes != null && i < routes.size(); i++) {
            PointList route = routes.get(i);
            double[] angles = turnAngles(route);
            for (int t = 0; t < angles.length; t++) {
                totalBending += angles[t] * angles[t];
                turns++;
            }
            for (int p = 1; p < route.size(); p++) {
                Point a = route.getPoint(p - 1), b = route.getPoint(p);
                totalLength += Math.hypot(b.x - a.x, b.y - a.y);
                segments++;
            }
        }

        double totalComponentArea = 0, totalContentArea = 0;
        for (int i = 0; componentBoxes != null && i < componentBoxes.size(); i++) {
            Rectangle box = componentBoxes.get(i);
            totalComponentArea += (double) box.width * box.height;
            totalContentArea += contentAreas.get(i).doubleValue();
        }

        double totalOverlapArea = 0, totalBoxArea = 0;
        for (int i = 0; nodeBoxes != null && i < nodeBoxes.size(); i++) {
            totalBoxArea += (double) nodeBoxes.get(i).width * nodeBoxes.get(i).height;
            for (int j = i + 1; j < nodeBoxes.size(); j++)
                totalOverlapArea += intersectionArea(nodeBoxes.get(i), nodeBoxes.get(j));
        }

        // A drawing with no components is not infinitely sprawling, it is simply not being asked
        // the question; 1.0 is the neutral value, the same as a box with no slack in it.
        double sprawl = totalContentArea > 0 ? totalComponentArea / totalContentArea : 1.0;
        double overlap = totalBoxArea > 0 ? totalOverlapArea / totalBoxArea : 0.0;
        double bending = turns > 0 ? totalBending / turns : 0.0;
        double spread = segments > 0 ? totalLength / segments / NATURAL_SPACING : 0.0;

        // Per segment drawn, so a big map is not judged worse for having more chances to cross.
        int crossings = crossings(routes);
        double crossingRate = segments > 0 ? (double) crossings / segments : 0.0;

        return new Score(bending, sprawl, overlap, spread, crossingRate, crossings, totalBending, totalComponentArea, totalContentArea,
                totalOverlapArea, totalLength, turns, componentBoxes == null ? 0 : componentBoxes.size(),
                nodeBoxes == null ? 0 : nodeBoxes.size(), segments);
    }

    /**
     * The path, as the polylines the spline will actually interpolate.
     *
     * <p>
     * That sequence of points and not the model's connections is what decides how a path looks,
     * because {@code BSpline} passes exactly through each of them -- see {@link ChainPlacement}.
     *
     * <p>
     * Chains are <b>stitched back together</b> through any junction the path merely passes through
     * -- one chain in, one chain out -- rather than measured one at a time. Taking each chain
     * separately would never measure the turn <i>at</i> a junction, and on a map with components
     * that is most of the corners there are: a node bound to a component is a junction however
     * ordinary it looks, so the sample's 30 path segments meet at only 12 chain-interior vertices.
     * An objective blind to the other corners would let a solver put a right angle at every one of
     * them for free, which is exactly the plunging the redesign is meant to end.
     *
     * <p>
     * A genuine fork or join is left as a break, because the turn there is not well defined: which
     * outgoing branch continues the incoming one is a question the model does not answer.
     */
    public static List<PointList> routesOf(UcmPathDecomposition decomposition, Map<IURNNode, Point> positions) {
        List<PointList> routes = new ArrayList<PointList>();
        if (decomposition == null)
            return routes;

        List<UcmPathDecomposition.Chain> chains = new ArrayList<UcmPathDecomposition.Chain>(decomposition.getChains());
        Map<IURNNode, List<UcmPathDecomposition.Chain>> leaving = new LinkedHashMap<IURNNode, List<UcmPathDecomposition.Chain>>();
        Map<IURNNode, int[]> arriving = new LinkedHashMap<IURNNode, int[]>();

        for (int i = 0; i < chains.size(); i++) {
            UcmPathDecomposition.Chain chain = chains.get(i);
            List<UcmPathDecomposition.Chain> out = leaving.get(chain.getFrom());
            if (out == null)
                leaving.put(chain.getFrom(), out = new ArrayList<UcmPathDecomposition.Chain>());
            out.add(chain);

            int[] count = arriving.get(chain.getTo());
            if (count == null)
                arriving.put(chain.getTo(), count = new int[1]);
            count[0]++;
        }

        Set<UcmPathDecomposition.Chain> used = new LinkedHashSet<UcmPathDecomposition.Chain>();

        // Start where a route can only start -- anywhere the path branches, merges or ends -- and
        // run through the pass-through junctions. Then pick up whatever is left, which is a loop of
        // pass-throughs that nothing enters, so any of its chains will do as a beginning.
        for (int i = 0; i < chains.size(); i++)
            if (!passesThrough(chains.get(i).getFrom(), leaving, arriving))
                addRoute(routes, walk(chains.get(i), used, leaving, arriving, positions));

        for (int i = 0; i < chains.size(); i++)
            if (!used.contains(chains.get(i)))
                addRoute(routes, walk(chains.get(i), used, leaving, arriving, positions));

        return routes;
    }

    /** Whether the path merely passes through this junction, so the turn at it is well defined. */
    private static boolean passesThrough(IURNNode node, Map<IURNNode, List<UcmPathDecomposition.Chain>> leaving, Map<IURNNode, int[]> arriving) {
        List<UcmPathDecomposition.Chain> out = leaving.get(node);
        int[] in = arriving.get(node);
        return out != null && out.size() == 1 && in != null && in[0] == 1;
    }

    /** Follows one chain into the next for as long as the junctions between them are pass-throughs. */
    private static PointList walk(UcmPathDecomposition.Chain start, Set<UcmPathDecomposition.Chain> used,
            Map<IURNNode, List<UcmPathDecomposition.Chain>> leaving, Map<IURNNode, int[]> arriving, Map<IURNNode, Point> positions) {

        PointList route = new PointList();
        if (!used.add(start))
            return route;

        addIfPlaced(route, positions, start.getFrom());
        UcmPathDecomposition.Chain chain = start;

        while (true) {
            for (Iterator<ucm.map.PathNode> n = chain.getInterior().iterator(); n.hasNext();)
                addIfPlaced(route, positions, n.next());
            addIfPlaced(route, positions, chain.getTo());

            if (!passesThrough(chain.getTo(), leaving, arriving))
                return route;

            UcmPathDecomposition.Chain next = leaving.get(chain.getTo()).get(0);
            if (!used.add(next))
                return route; // a loop closed on itself; stop rather than go round forever

            chain = next;
        }
    }

    private static void addRoute(List<PointList> routes, PointList route) {
        if (route.size() >= 2)
            routes.add(route);
    }

    private static void addIfPlaced(PointList route, Map<IURNNode, Point> positions, IURNNode node) {
        Point at = node == null ? null : positions.get(node);
        if (at != null)
            route.addPoint(at);
    }

    /**
     * The box each component implies and the area its contents actually occupy.
     *
     * <p>
     * A node counts towards every container that holds it, however deeply nested, so an actor's
     * box is measured against everything drawn inside it rather than only its direct children.
     * Sizing a box the same way {@code ComponentSeparation} does is what makes the number comparable
     * with what gets drawn.
     *
     * @param boxes
     *            filled with one rectangle per component that holds anything
     * @param contentAreas
     *            filled in step with {@code boxes}: the summed area of that component's nodes
     */
    public static void componentBoxesOf(Map<IURNNode, Point> positions, Map<IURNNode, Dimension> sizes, int margin, List<Rectangle> boxes,
            List<Double> contentAreas) {

        Map<Object, List<IURNNode>> held = new LinkedHashMap<Object, List<IURNNode>>();

        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            for (IURNContainerRef ref = node.getContRef(); ref != null; ref = ref.getParent()) {
                List<IURNNode> members = held.get(ref);
                if (members == null)
                    held.put(ref, members = new ArrayList<IURNNode>());
                members.add(node);
            }
        }

        for (Iterator<List<IURNNode>> it = held.values().iterator(); it.hasNext();) {
            List<IURNNode> members = it.next();

            int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
            double content = 0;

            for (int i = 0; i < members.size(); i++) {
                Rectangle box = boxOf(members.get(i), positions, sizes);
                left = Math.min(left, box.x);
                top = Math.min(top, box.y);
                right = Math.max(right, box.right());
                bottom = Math.max(bottom, box.bottom());
                content += (double) box.width * box.height;
            }

            if (left == Integer.MAX_VALUE)
                continue;

            boxes.add(new Rectangle(left - margin, top - margin, right - left + 2 * margin, bottom - top + 2 * margin));
            contentAreas.add(Double.valueOf(content));
        }
    }

    /** A node's drawn rectangle: its extent, centred on where it was placed. */
    public static Rectangle boxOf(IURNNode node, Map<IURNNode, Point> positions, Map<IURNNode, Dimension> sizes) {
        Point at = positions.get(node);
        if (at == null)
            return new Rectangle(0, 0, 0, 0);

        Dimension size = sizes == null ? null : sizes.get(node);
        int width = size == null ? 0 : size.width;
        int height = size == null ? 0 : size.height;
        return new Rectangle(at.x - width / 2, at.y - height / 2, width, height);
    }

    // ------------------------------------------------------------------------------ arithmetic

    /**
     * How many times the drawn paths cross each other.
     *
     * <p>
     * Not one of the four terms on issue #30, and the omission turned out to matter: a solver that
     * optimises only those four can beat PM4Py-UCM's layout on every one of them and still produce
     * a drawing nobody can follow, because it spends the freedom it gains on crossings that no term
     * charges it for. Graphviz's crossing minimisation had been quietly supplying this all along.
     *
     * <p>
     * {@link Score#crossingRate} folds this into the total, per segment drawn so that a large map
     * is not judged worse merely for having more chances to cross. The raw count stays available
     * because it is the number a person actually recognises in a drawing.
     */
    public static int crossings(List<PointList> routes) {
        int count = 0;
        if (routes == null)
            return 0;

        for (int i = 0; i < routes.size(); i++) {
            for (int j = i; j < routes.size(); j++) {
                PointList a = routes.get(i), b = routes.get(j);
                for (int s = 1; s < a.size(); s++) {
                    // Within one route, only non-adjacent segments can cross; consecutive ones
                    // merely meet at the node between them.
                    for (int t = (i == j ? s + 2 : 1); t < b.size(); t++)
                        if (crosses(a.getPoint(s - 1), a.getPoint(s), b.getPoint(t - 1), b.getPoint(t)))
                            count++;
                }
            }
        }
        return count;
    }

    /** Whether two segments properly cross. Sharing an endpoint is meeting, not crossing. */
    private static boolean crosses(Point p1, Point p2, Point p3, Point p4) {
        if (p1.equals(p3) || p1.equals(p4) || p2.equals(p3) || p2.equals(p4))
            return false;

        double d1 = side(p3, p4, p1), d2 = side(p3, p4, p2), d3 = side(p1, p2, p3), d4 = side(p1, p2, p4);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    /** Which side of the line a-b the point c lies on. */
    private static double side(Point a, Point b, Point c) {
        return (double) (b.x - a.x) * (c.y - a.y) - (double) (b.y - a.y) * (c.x - a.x);
    }

    /**
     * How much two rectangles share, in square pixels; 0 when they do not meet.
     *
     * <p>
     * Computed rather than delegated so the answer does not depend on which draw2d decides that
     * touching edges intersect.
     */
    public static double intersectionArea(Rectangle a, Rectangle b) {
        double width = Math.min(a.right(), b.right()) - Math.max(a.x, b.x);
        double height = Math.min(a.bottom(), b.bottom()) - Math.max(a.y, b.y);
        return width <= 0 || height <= 0 ? 0 : width * height;
    }

    /**
     * The turn at each interior vertex of a polyline, in radians: 0 straight on, pi a reversal.
     *
     * <p>
     * The quantity {@link ChainPlacement} exists to keep small, since an interpolating spline
     * overshoots on a sharp turn. {@link ChainPlacement#sharpestTurn} reports the worst of these;
     * the objective wants all of them, because one bad corner and twenty mediocre ones are
     * different drawings and the maximum cannot tell them apart.
     */
    public static double[] turnAngles(PointList pts) {
        if (pts == null || pts.size() < 3)
            return new double[0];

        double[] angles = new double[pts.size() - 2];
        for (int i = 1; i < pts.size() - 1; i++) {
            Point a = pts.getPoint(i - 1), b = pts.getPoint(i), c = pts.getPoint(i + 1);
            double abx = b.x - a.x, aby = b.y - a.y;
            double bcx = c.x - b.x, bcy = c.y - b.y;
            double la = Math.hypot(abx, aby), lb = Math.hypot(bcx, bcy);
            if (la < MIN_SEGMENT || lb < MIN_SEGMENT)
                continue; // coincident points state no direction, so no turn is claimed

            double cos = (abx * bcx + aby * bcy) / (la * lb);
            angles[i - 1] = Math.acos(Math.max(-1, Math.min(1, cos)));
        }
        return angles;
    }
}
