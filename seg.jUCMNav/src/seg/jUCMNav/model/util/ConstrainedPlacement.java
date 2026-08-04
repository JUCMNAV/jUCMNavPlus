package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * Places a map's junctions by pushing them around until the drawing stops improving.
 *
 * <p>
 * This replaces the four-pass pipeline described on issue #30, where each pass partly undid the
 * last: Graphviz placed nodes freely and drew good paths with terrible components, {@code
 * ComponentSeparation} fixed the components and degraded the paths, the fork-pulling pass moved
 * forks and invalidated routes already computed. Those are not weights to tune. They are one
 * problem being solved four times, in different directions.
 *
 * <p>
 * <b>Why this is a small problem.</b> Two facts collapse it. A node bound to a component is already
 * a junction -- {@link UcmPathDecomposition#isJunction} says so, because which component performs it
 * decides where it goes -- so <i>every component member is a junction</i>. And once a chain is drawn
 * as a straight run between its two junctions, {@link ChainPlacement} spaces its interior evenly
 * along it, so the interior bends nowhere and contributes nothing but its share of the length.
 * Between them, all four terms of the objective become functions of the junction positions alone:
 * roughly fifteen points per map, not two hundred.
 *
 * <p>
 * <b>What moves it.</b> Six forces, each one term of the objective or one of its hard constraints:
 *
 * <ul>
 * <li><b>chain springs</b> hold each chain at the length its interior needs to sit at a comfortable
 * spacing -- this is the edge-length term, and it is what gives {@link ChainPlacement} an evenly
 * spaced run to distribute along;</li>
 * <li><b>straightening</b> pulls a junction the path merely passes through towards the line between
 * its neighbours -- the bending term, and the one that matters most, since an interpolating spline
 * overshoots on a sharp turn;</li>
 * <li><b>cohesion</b> pulls a component's members towards their common centre -- the area term;</li>
 * <li><b>node repulsion</b> pushes overlapping element-and-label boxes apart -- the overlap term;</li>
 * <li><b>component separation</b> translates whole components out of each other, and</li>
 * <li><b>ejection</b> pushes a node out of a component it does not belong to, but only when being
 * drawn inside one would mean something. A fork, a join, an empty point or a direction arrow is
 * pure shape and may lie anywhere -- that freedom is what lets a path run <i>through</i> a component
 * instead of lurching around it, and it is most of the room this has to work in.</li>
 * </ul>
 *
 * The last two are the hard constraints, and a penalty does not guarantee them, so they are also
 * enforced exactly at the end -- see {@link #solve}.
 *
 * <p>
 * What is deliberately absent is <b>rank</b>. URN has no notion of one, and importing one is what
 * every rejected approach did: a Graphviz cluster forces its members into adjacent ranks, which is
 * why a component acting at several points along a path made the path leave and re-enter it.
 *
 * <p>
 * Graphviz is still worth running first. Its crossing minimisation is genuinely good and a seed
 * that starts near a good arrangement converges to a better one than a seed that does not -- see
 * {@link #solve} for the measurement. But it is now only a starting guess.
 *
 * <p>
 * Pure geometry over a supplied map of positions, so it can be run and scored in a test with no
 * workbench -- and it is scored, by {@link LayoutObjective}, which is the only reason the constants
 * below are what they are.
 *
 * @author Claude
 */
public class ConstrainedPlacement {

    /**
     * How far apart consecutive path nodes want to sit, in pixels.
     *
     * <p>
     * Above {@link LayoutObjective#NATURAL_SPACING} on purpose: at 60 the element-and-label boxes
     * of neighbouring nodes touch, so repulsion fights the springs and the drawing never settles.
     * The hand-drawn sample sits at about 127. Measured across 60/90/120: 90 scores best.
     */
    private static final double REST_SPACING = 120.0;

    /** Clear space left between two component boxes. */
    private static final int SEPARATION = 24;

    private static final int ITERATIONS = 600;

    /** Furthest any node may move in one iteration, before cooling. */
    private static final double MAX_STEP = 50.0;

    // Force weights. Tuned against LayoutObjective on the issue-tracker sample and the demo sweep;
    // the ratios matter, not the absolute values, since the step is capped and cooled anyway.
    private static final double SPRING = 0.12;
    private static final double STRAIGHTEN = 0.35;
    private static final double COHESION = 0.02;
    private static final double REPULSION = 0.80;
    private static final double SEPARATE = 0.60;
    private static final double EJECT = 0.50;
    private static final double FLOW = 0.30;

    /**
     * How much of a chain's length must be forward progress before the flow force lets go.
     *
     * <p>
     * Raising it does not flatten the drawing, which is the obvious thing to try and it is wrong.
     * Forcing more of each chain to be horizontal progress drags a component's members apart along
     * x, and the component boxes inflate to hold them: measured at 0.75, sprawl went from 15.5 to
     * 18.7 and the total from 17.8 to 21.0, while the drawing got no flatter than 1.44:1. The
     * drawing is square because the objective has no term for its shape, not because this is low.
     */
    private static final double FLOW_FRACTION = 0.5;

    /**
     * How far apart two branches sharing a fork and a join are bowed, in pixels.
     *
     * <p>
     * <b>Unexercised by the issue-tracker sample</b>, whose branches each contain a node bound to a
     * component -- which makes it a junction, which splits the branch into chains with different
     * endpoints. Scores are byte-identical with and without it there. It is kept because the case
     * is real -- a plain fork and join with only empty points between them gives two chains the
     * same pair of endpoints, so the same straight run, so one branch drawn on top of the other --
     * but it wants a model that actually has one before it can be called verified.
     */
    private static final double BOW = 90.0;

    /** Below this, two points are in the same place and their direction is meaningless. */
    private static final double EPSILON = 1e-6;

    private ConstrainedPlacement() {
    }

    /**
     * Moves the junctions in {@code positions} to where the drawing is best.
     *
     * <p>
     * The descent uses penalties for the two hard constraints, which pushes towards satisfying them
     * but cannot promise it. Position is semantics in URN and the OCL rules are not advisory, so
     * afterwards {@link ComponentSeparation} runs once as an exact repair. That is not the old pass
     * returning: by then the solver has usually already satisfied the constraint, and how far the
     * repair still has to move things is a measurement worth watching -- when it reaches zero
     * across the sweep, the repair can go.
     *
     * @param decomposition
     *            the map's junctions and chains
     * @param positions
     *            junction -&gt; centre, seeded from Graphviz. Modified in place.
     * @param sizes
     *            junction -&gt; how big it is drawn including its label, as {@link LabelExtent}
     *            reports it
     * @param margin
     *            the margin a component's rectangle is drawn with around its contents
     * @return the same map, for chaining
     */
    public static Map<IURNNode, Point> solve(UcmPathDecomposition decomposition, Map<IURNNode, Point> positions,
            Map<IURNNode, Dimension> sizes, int margin) {

        if (decomposition == null || positions == null || positions.size() < 2)
            return positions;

        List<IURNNode> nodes = new ArrayList<IURNNode>(positions.keySet());
        int n = nodes.size();

        Map<IURNNode, Integer> index = new LinkedHashMap<IURNNode, Integer>();
        for (int i = 0; i < n; i++)
            index.put(nodes.get(i), Integer.valueOf(i));

        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            Point at = positions.get(nodes.get(i));
            x[i] = at.x;
            y[i] = at.y;
        }

        int[][] springs = springsOf(decomposition, index);
        int[][] corners = cornersOf(decomposition, index);
        List<int[]> groups = groupsOf(nodes, index);

        double[] dx = new double[n], dy = new double[n];

        for (int pass = 0; pass < ITERATIONS; pass++) {
            for (int i = 0; i < n; i++) {
                dx[i] = 0;
                dy[i] = 0;
            }

            applySprings(springs, x, y, dx, dy);
            applyFlow(springs, x, dx);
            applyStraightening(corners, x, y, dx, dy);
            applyCohesion(groups, x, y, dx, dy);
            applyRepulsion(nodes, sizes, x, y, dx, dy);
            applySeparation(groups, nodes, sizes, margin, x, y, dx, dy);
            applyEjection(groups, nodes, sizes, margin, x, y, dx, dy);

            // Cooling. Early passes may move a node right across the drawing to escape a bad seed;
            // late ones may only settle it, so the arrangement stops changing instead of buzzing
            // between two equally good arrangements forever.
            double cap = 1.0 + MAX_STEP * (1.0 - (double) pass / ITERATIONS);
            for (int i = 0; i < n; i++) {
                double len = Math.hypot(dx[i], dy[i]);
                if (len < EPSILON)
                    continue;
                double scale = Math.min(len, cap) / len;
                x[i] += dx[i] * scale;
                y[i] += dy[i] * scale;
            }
        }

        for (int i = 0; i < n; i++)
            positions.put(nodes.get(i), new Point((int) Math.round(x[i]), (int) Math.round(y[i])));

        // The hard constraints, exactly. A penalty gets close; the OCL rules want close enough to
        // be no overlap at all.
        ComponentSeparation.apply(positions, sizes, margin);
        return positions;
    }

    /**
     * Strings each chain's interior along the straight run between its two junctions.
     *
     * <p>
     * Straight, and not the spline Graphviz drew, because the solver has moved the junctions and a
     * route is only valid for the endpoints it was computed for. Pinning the ends of an old spline
     * to new positions while keeping its middle spikes the curve -- that was tried, and it looks
     * worse than the straight line it replaced.
     *
     * <p>
     * Nothing is lost by it. A Graphviz route earns its shape by dodging obstacles, and the solver
     * has already moved the obstacles out of the way; what is left to do between two junctions is
     * to space the nodes evenly, which is what {@link ChainPlacement} does and what an
     * interpolating spline needs.
     *
     * @param positions
     *            junction positions, as {@link #solve} left them; interiors are added
     * @return the same map, now holding every node of the map
     */
    public static Map<IURNNode, Point> placeChainInteriors(UcmPathDecomposition decomposition, Map<IURNNode, Point> positions) {
        if (decomposition == null || positions == null)
            return positions;

        Map<UcmPathDecomposition.Chain, int[]> siblings = siblingsOf(decomposition);

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            if (chain.length() == 0)
                continue;

            Point from = positions.get(chain.getFrom());
            Point to = positions.get(chain.getTo());
            if (from == null || to == null)
                continue;

            org.eclipse.draw2d.geometry.PointList route = new org.eclipse.draw2d.geometry.PointList();
            route.addPoint(from);

            // Two branches of a fork that rejoin at the same join have the same two endpoints, so a
            // straight run gives them the same route and their nodes land on top of each other --
            // one branch drawn, the other hidden underneath it. Bow them apart, which is what a
            // person draws and what makes the alternatives legible as alternatives.
            int[] sibling = siblings.get(chain);
            if (sibling != null && sibling[1] > 1) {
                double offset = BOW * (sibling[0] - (sibling[1] - 1) / 2.0);
                double vx = to.x - from.x, vy = to.y - from.y;
                double len = Math.hypot(vx, vy);
                if (len > EPSILON)
                    route.addPoint(new Point((int) Math.round((from.x + to.x) / 2.0 - vy / len * offset),
                            (int) Math.round((from.y + to.y) / 2.0 + vx / len * offset)));
            }

            route.addPoint(to);

            org.eclipse.draw2d.geometry.PointList spread = ChainPlacement.distribute(route, chain.length());
            List<ucm.map.PathNode> interior = chain.getInterior();
            for (int i = 0; i < interior.size() && i < spread.size(); i++)
                positions.put(interior.get(i), spread.getPoint(i));
        }
        return positions;
    }

    /**
     * Which chains share both endpoints with another, as (position among them, how many).
     *
     * Keyed on the unordered pair, so a branch that runs the other way still counts as a sibling of
     * the one it parallels.
     */
    private static Map<UcmPathDecomposition.Chain, int[]> siblingsOf(UcmPathDecomposition decomposition) {
        Map<String, List<UcmPathDecomposition.Chain>> byPair = new LinkedHashMap<String, List<UcmPathDecomposition.Chain>>();

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            List<UcmPathDecomposition.Chain> at = byPair.get(key(chain));
            if (at == null)
                byPair.put(key(chain), at = new ArrayList<UcmPathDecomposition.Chain>());
            at.add(chain);
        }

        Map<UcmPathDecomposition.Chain, int[]> siblings = new LinkedHashMap<UcmPathDecomposition.Chain, int[]>();
        for (Iterator<List<UcmPathDecomposition.Chain>> it = byPair.values().iterator(); it.hasNext();) {
            List<UcmPathDecomposition.Chain> group = it.next();
            for (int i = 0; i < group.size(); i++)
                siblings.put(group.get(i), new int[] { i, group.size() });
        }
        return siblings;
    }

    /** The unordered endpoint pair of a chain, and then the chain itself, as a lookup key. */
    private static String key(UcmPathDecomposition.Chain chain) {
        int a = System.identityHashCode(chain.getFrom()), b = System.identityHashCode(chain.getTo());
        return Math.min(a, b) + ":" + Math.max(a, b); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------------------- forces

    /**
     * Each chain wants to be as long as the nodes strung along it need.
     *
     * A chain with four interior nodes has five gaps to fill, so it wants to be five comfortable
     * gaps long -- shorter and {@link ChainPlacement} crams them, longer and it strings them out.
     */
    private static void applySprings(int[][] springs, double[] x, double[] y, double[] dx, double[] dy) {
        for (int s = 0; s < springs.length; s++) {
            int a = springs[s][0], b = springs[s][1];
            double rest = springs[s][2];

            double vx = x[b] - x[a], vy = y[b] - y[a];
            double len = Math.hypot(vx, vy);
            if (len < EPSILON)
                continue;

            double pull = SPRING * (len - rest) / len;
            dx[a] += vx * pull;
            dy[a] += vy * pull;
            dx[b] -= vx * pull;
            dy[b] -= vy * pull;
        }
    }

    /**
     * A chain that is not a loop wants to end to the right of where it started.
     *
     * <p>
     * This is the one thing Graphviz was supplying that none of the four terms on issue #30 asks
     * for. Optimise those four alone and the solver wins on every one of them while producing a
     * drawing nobody can read: it spends the freedom on path crossings, which no term charges it
     * for, and on losing the left-to-right reading that makes a start point a beginning.
     *
     * <p>
     * This is <b>not</b> the rank that every rejected approach imported. A rank forces nodes into
     * discrete layers and makes a component's members contiguous, which is the constraint that
     * tangled the path when clusters were tried. This only says a step forward should not go
     * backwards, and only by enough to break the tie -- half a chain's length, so a chain is free
     * to run diagonally or almost vertically, and only an actual reversal is opposed. A loop's back
     * edge is exempt, since going back is what it is for.
     */
    private static void applyFlow(int[][] springs, double[] x, double[] dx) {
        for (int s = 0; s < springs.length; s++) {
            if (springs[s][3] == 1)
                continue; // a loop closes backwards on purpose

            int a = springs[s][0], b = springs[s][1];
            double wanted = springs[s][2] * FLOW_FRACTION;
            double gap = x[b] - x[a];
            if (gap >= wanted)
                continue;

            double push = FLOW * (wanted - gap) / 2.0;
            dx[a] -= push;
            dx[b] += push;
        }
    }

    /**
     * A junction the path merely passes through is pulled towards the midpoint of its neighbours.
     *
     * <p>
     * Which is to say towards the straight line between them, and to the middle of it -- so the
     * turn at that junction opens out and the two gaps either side of it even up. Both are exactly
     * what an interpolating spline needs: it overshoots on a sharp turn or on uneven spacing, and
     * this is the only force that addresses either.
     *
     * <p>
     * Only where the path passes straight through. At a genuine fork or join, which branch
     * continues the incoming path is not something the model says, so there is no turn to open.
     */
    private static void applyStraightening(int[][] corners, double[] x, double[] y, double[] dx, double[] dy) {
        for (int c = 0; c < corners.length; c++) {
            int prev = corners[c][0], at = corners[c][1], next = corners[c][2];

            double offX = x[at] - (x[prev] + x[next]) / 2.0;
            double offY = y[at] - (y[prev] + y[next]) / 2.0;

            // The neighbours take the reaction, half each. Pulling only the middle junction cannot
            // straighten anything: the springs fix the two chain lengths, so if the neighbours sit
            // closer together than those two lengths, the middle *must* bulge and no amount of pull
            // towards the midpoint will do anything but fight the springs. It has to be able to
            // move the neighbours apart, and letting them take the reaction is what does that --
            // measured as the difference between a 144-degree fold at every junction and none.
            //
            // Equal and opposite, so the drawing does not drift while it settles.
            dx[at] -= STRAIGHTEN * offX;
            dy[at] -= STRAIGHTEN * offY;
            dx[prev] += STRAIGHTEN * offX / 2.0;
            dy[prev] += STRAIGHTEN * offY / 2.0;
            dx[next] += STRAIGHTEN * offX / 2.0;
            dy[next] += STRAIGHTEN * offY / 2.0;
        }
    }

    /** A component's members are pulled towards their common centre, which shrinks its box. */
    private static void applyCohesion(List<int[]> groups, double[] x, double[] y, double[] dx, double[] dy) {
        for (int g = 0; g < groups.size(); g++) {
            int[] members = groups.get(g);
            if (members.length < 2)
                continue;

            double cx = 0, cy = 0;
            for (int i = 0; i < members.length; i++) {
                cx += x[members[i]];
                cy += y[members[i]];
            }
            cx /= members.length;
            cy /= members.length;

            for (int i = 0; i < members.length; i++) {
                dx[members[i]] += COHESION * (cx - x[members[i]]);
                dy[members[i]] += COHESION * (cy - y[members[i]]);
            }
        }
    }

    /** Overlapping element-and-label boxes push each other apart, along the axis of least overlap. */
    private static void applyRepulsion(List<IURNNode> nodes, Map<IURNNode, Dimension> sizes, double[] x, double[] y, double[] dx,
            double[] dy) {

        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Dimension a = extent(sizes, nodes.get(i));
                Dimension b = extent(sizes, nodes.get(j));

                double overlapX = (a.width + b.width) / 2.0 - Math.abs(x[i] - x[j]);
                double overlapY = (a.height + b.height) / 2.0 - Math.abs(y[i] - y[j]);
                if (overlapX <= 0 || overlapY <= 0)
                    continue;

                // Least-overlap axis: the smallest correction that separates them, so the drawing
                // is disturbed as little as the constraint allows.
                if (overlapX <= overlapY) {
                    double push = REPULSION * overlapX / 2.0 * (x[i] <= x[j] ? -1 : 1);
                    dx[i] += push;
                    dx[j] -= push;
                } else {
                    double push = REPULSION * overlapY / 2.0 * (y[i] <= y[j] ? -1 : 1);
                    dy[i] += push;
                    dy[j] -= push;
                }
            }
        }
    }

    /** Whole components are translated out of each other. */
    private static void applySeparation(List<int[]> groups, List<IURNNode> nodes, Map<IURNNode, Dimension> sizes, int margin, double[] x,
            double[] y, double[] dx, double[] dy) {

        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                Rectangle a = box(groups.get(i), nodes, sizes, margin, x, y);
                Rectangle b = box(groups.get(j), nodes, sizes, margin, x, y);

                double[] push = pushApart(a, b);
                if (push == null)
                    continue;

                translate(groups.get(i), dx, dy, -SEPARATE * push[0] / 2, -SEPARATE * push[1] / 2);
                translate(groups.get(j), dx, dy, SEPARATE * push[0] / 2, SEPARATE * push[1] / 2);
            }
        }
    }

    /**
     * A node is pushed out of a component it does not belong to -- but only if it would be read as
     * belonging.
     *
     * <p>
     * A responsibility drawn inside a component says that component performs it, and a start or end
     * point belongs to one, so putting either in the wrong box says something false. A fork, a join,
     * an empty point or a direction arrow marks where a path branches or bends and asserts nothing
     * about who does the work, so it may lie wherever it falls. Treating every node as untouchable
     * was what forced the path to dive away from every component it passed.
     */
    private static void applyEjection(List<int[]> groups, List<IURNNode> nodes, Map<IURNNode, Dimension> sizes, int margin, double[] x,
            double[] y, double[] dx, double[] dy) {

        for (int g = 0; g < groups.size(); g++) {
            int[] members = groups.get(g);
            Rectangle box = box(members, nodes, sizes, margin, x, y);

            for (int i = 0; i < nodes.size(); i++) {
                if (contains(members, i) || !ComponentSeparation.bindingIsMeaningful(nodes.get(i)))
                    continue;

                Dimension size = extent(sizes, nodes.get(i));
                Rectangle here = new Rectangle((int) Math.round(x[i] - size.width / 2.0), (int) Math.round(y[i] - size.height / 2.0),
                        size.width, size.height);

                double[] push = pushApart(box, here);
                if (push == null)
                    continue;

                dx[i] += EJECT * push[0];
                dy[i] += EJECT * push[1];
            }
        }
    }

    // ------------------------------------------------------------------------------ structure

    /** One spring per chain: the two junctions, and how long the interior needs it to be. */
    private static int[][] springsOf(UcmPathDecomposition decomposition, Map<IURNNode, Integer> index) {
        List<int[]> springs = new ArrayList<int[]>();

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            Integer a = index.get(chain.getFrom());
            Integer b = index.get(chain.getTo());
            if (a == null || b == null || a.equals(b))
                continue;

            // n interior nodes make n+1 gaps to fill.
            springs.add(new int[] { a.intValue(), b.intValue(), (int) Math.round((chain.length() + 1) * REST_SPACING),
                    decomposition.isBackEdge(chain) ? 1 : 0 });
        }
        return springs.toArray(new int[springs.size()][]);
    }

    /**
     * Every junction the path passes straight through, as (previous, it, next).
     *
     * These are the corners the bending term can see, and the only ones where straightening means
     * anything -- see {@link LayoutObjective#routesOf}, which stitches chains at the same places.
     */
    private static int[][] cornersOf(UcmPathDecomposition decomposition, Map<IURNNode, Integer> index) {
        Map<IURNNode, List<UcmPathDecomposition.Chain>> leaving = new LinkedHashMap<IURNNode, List<UcmPathDecomposition.Chain>>();
        Map<IURNNode, List<UcmPathDecomposition.Chain>> entering = new LinkedHashMap<IURNNode, List<UcmPathDecomposition.Chain>>();

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            add(leaving, chain.getFrom(), chain);
            add(entering, chain.getTo(), chain);
        }

        List<int[]> corners = new ArrayList<int[]>();
        for (Iterator<IURNNode> it = index.keySet().iterator(); it.hasNext();) {
            IURNNode at = it.next();
            List<UcmPathDecomposition.Chain> in = entering.get(at);
            List<UcmPathDecomposition.Chain> out = leaving.get(at);
            if (in == null || out == null || in.size() != 1 || out.size() != 1)
                continue;

            Integer prev = index.get(in.get(0).getFrom());
            Integer here = index.get(at);
            Integer next = index.get(out.get(0).getTo());
            if (prev == null || next == null || prev.equals(here) || next.equals(here))
                continue;

            corners.add(new int[] { prev.intValue(), here.intValue(), next.intValue() });
        }
        return corners.toArray(new int[corners.size()][]);
    }

    private static void add(Map<IURNNode, List<UcmPathDecomposition.Chain>> to, IURNNode key, UcmPathDecomposition.Chain chain) {
        List<UcmPathDecomposition.Chain> at = to.get(key);
        if (at == null)
            to.put(key, at = new ArrayList<UcmPathDecomposition.Chain>());
        at.add(chain);
    }

    /** The nodes of each outermost component, by index. A nested component moves with its parent. */
    private static List<int[]> groupsOf(List<IURNNode> nodes, Map<IURNNode, Integer> index) {
        Map<Object, List<Integer>> byOwner = new LinkedHashMap<Object, List<Integer>>();

        for (int i = 0; i < nodes.size(); i++) {
            IURNContainerRef ref = nodes.get(i).getContRef();
            if (ref == null)
                continue;
            while (ref.getParent() != null)
                ref = ref.getParent();

            List<Integer> members = byOwner.get(ref);
            if (members == null)
                byOwner.put(ref, members = new ArrayList<Integer>());
            members.add(Integer.valueOf(i));
        }

        List<int[]> groups = new ArrayList<int[]>();
        for (Iterator<List<Integer>> it = byOwner.values().iterator(); it.hasNext();) {
            List<Integer> members = it.next();
            int[] group = new int[members.size()];
            for (int i = 0; i < group.length; i++)
                group[i] = members.get(i).intValue();
            groups.add(group);
        }
        return groups;
    }

    // ------------------------------------------------------------------------------ geometry

    /** How far {@code b} must move to clear {@code a}, along the axis of least overlap, or null. */
    private static double[] pushApart(Rectangle a, Rectangle b) {
        if (!a.intersects(b))
            return null;

        double right = a.right() - b.x + SEPARATION;
        double left = b.right() - a.x + SEPARATION;
        double down = a.bottom() - b.y + SEPARATION;
        double up = b.bottom() - a.y + SEPARATION;

        double px = right < left ? right : -left;
        double py = down < up ? down : -up;

        return Math.abs(px) <= Math.abs(py) ? new double[] { px, 0 } : new double[] { 0, py };
    }

    private static Rectangle box(int[] members, List<IURNNode> nodes, Map<IURNNode, Dimension> sizes, int margin, double[] x, double[] y) {
        double left = Double.MAX_VALUE, top = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottom = -Double.MAX_VALUE;

        for (int i = 0; i < members.length; i++) {
            int m = members[i];
            Dimension size = extent(sizes, nodes.get(m));
            left = Math.min(left, x[m] - size.width / 2.0);
            top = Math.min(top, y[m] - size.height / 2.0);
            right = Math.max(right, x[m] + size.width / 2.0);
            bottom = Math.max(bottom, y[m] + size.height / 2.0);
        }

        if (left == Double.MAX_VALUE)
            return new Rectangle(0, 0, 0, 0);

        return new Rectangle((int) Math.round(left - margin), (int) Math.round(top - margin), (int) Math.round(right - left + 2 * margin),
                (int) Math.round(bottom - top + 2 * margin));
    }

    private static void translate(int[] members, double[] dx, double[] dy, double byX, double byY) {
        for (int i = 0; i < members.length; i++) {
            dx[members[i]] += byX;
            dy[members[i]] += byY;
        }
    }

    private static boolean contains(int[] members, int i) {
        for (int m = 0; m < members.length; m++)
            if (members[m] == i)
                return true;
        return false;
    }

    private static Dimension extent(Map<IURNNode, Dimension> sizes, IURNNode node) {
        Dimension size = sizes == null ? null : sizes.get(node);
        return size == null ? new Dimension(0, 0) : size;
    }
}
