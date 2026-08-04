package seg.jUCMNav.importexport;

import java.util.Iterator;

import seg.jUCMNav.model.util.UcmPathDecomposition;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urncore.URNmodelElement;

/**
 * The DOT that asks Graphviz only the question it is good at.
 *
 * <p>
 * {@link ExportLayoutDOT} hands over the whole map -- every empty point, every responsibility -- and
 * asks for a layered drawing. That is a 200-node problem where the real one has about fifteen
 * degrees of freedom: everything between two junctions has no choice about where it goes, and
 * spending Graphviz's crossing minimisation on it makes the answer worse, not better, because rank
 * assignment then dictates the spacing of nodes whose spacing is what decides how the curve looks.
 *
 * <p>
 * So this emits the contracted graph: the junctions from {@link UcmPathDecomposition} and
 * <b>one edge per chain</b>, carrying a {@code minlen} proportional to how many nodes have to fit
 * along it. The chain interiors are placed afterwards by {@code ChainPlacement} along the route
 * Graphviz chose.
 *
 * <p>
 * <b>Components are clusters, because a UCM component is a two-dimensional box.</b> It has a
 * position and a size, it nests, and the rules say it must not overlap another and must contain
 * exactly the nodes bound to it. A Graphviz cluster is the same shape of thing, and it satisfies
 * both rules by construction -- measured against jUCMNav's own OCL layout rules, the reporter's map
 * comes out with zero violations.
 *
 * <p>
 * Two alternatives were tried and measured on that map. Emitting no clusters at all draws a clean
 * left-to-right flow but is <i>illegal</i>: twelve rule violations, components overlapping and
 * seven path nodes inside a component that does not perform them. Forcing each component into a
 * horizontal band is legal but throws away a dimension -- it is a BPMN swimlane, not a UCM
 * component -- and the path then dives from band to band.
 *
 * <p>
 * Clusters are legal and keep both dimensions, at the cost of a tangle: dot additionally packs a
 * cluster's members into adjacent ranks, a constraint UCM does not impose, so a path visiting a
 * component at several points has to leave and re-enter it. That extra constraint, not the box, is
 * what remains to be removed -- see #30.
 *
 * @author Claude
 */
public class ExportContractedDOT {

    /** Points per rank, roughly. Chains ask for room in these units via minlen. */
    private static final int NODES_PER_RANK = 1;

    /** Even an empty chain needs its two junctions kept apart. */
    private static final int MIN_RANK_SEPARATION = 1;

    public static String convert(UCMmap map, UcmPathDecomposition decomposition) {
        StringBuffer dot = new StringBuffer();
        String id = ((URNmodelElement) map).getId();

        dot.append("digraph " + AutoLayoutPreferences.DIAGPREFIX + id + " {\n"); //$NON-NLS-1$ //$NON-NLS-2$
        dot.append("rankdir=\"" + AutoLayoutPreferences.getOrientation() + "\";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        dot.append("size=\"" + AutoLayoutPreferences.getWidth() + "," + AutoLayoutPreferences.getHeight() + "\";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Splines, because the routes are what the chains get distributed along. With polylines
        // the chain interiors would inherit the corners instead of curving around them.
        dot.append("splines=\"spline\";\nnodesep=\"0.6\";\nranksep=\"0.6\";\n"); //$NON-NLS-1$

        boolean clusters = !"false".equals(System.getProperty("jucmnav.layout.clusters", "true"));
        if (clusters) {
            for (Iterator<?> it = map.getContRefs().iterator(); it.hasNext();) {
                urncore.IURNContainerRef ref = (urncore.IURNContainerRef) it.next();
                if (ref.getParent() == null)
                    cluster(ref, decomposition, dot);
            }
        }

        // The spine -- the longest run from a start point to an end point -- is what a reader
        // follows, and in a hand-drawn map it is a straight line with everything else arranged
        // around it. group= asks dot to keep those junctions colinear; weight asks it to keep the
        // edges between them short and straight, at the expense of bending the side branches
        // instead. Without it dot has no reason to prefer any one route through the graph, which
        // is why the path wandered.
        java.util.Set<PathNode> spine = decomposition.getSpine();

        for (Iterator<PathNode> it = decomposition.getJunctions().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (!clusters || pn.getContRef() == null)
                dot.append(node(pn, spine.contains(pn)));
        }

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            if (!(chain.getFrom() instanceof PathNode) || !(chain.getTo() instanceof PathNode))
                continue;

            int room = MIN_RANK_SEPARATION + chain.length() * NODES_PER_RANK;

            // A loop's back edge is routed but not ranked. Ranking it would drag its target below
            // its source and stretch the drawing to satisfy an order that cannot hold.
            String constraint = decomposition.isBackEdge(chain) ? ", constraint=\"false\"" : ""; //$NON-NLS-1$ //$NON-NLS-2$

            dot.append(name((PathNode) chain.getFrom()) + " -> " + name((PathNode) chain.getTo()) //$NON-NLS-1$
                    + " [minlen=\"" + room + "\"" + constraint + "];\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        dot.append("}\n"); //$NON-NLS-1$
        return dot.toString();
    }

    /** A component as a 2-D box: Graphviz keeps a cluster's members together and clusters apart. */
    private static void cluster(urncore.IURNContainerRef ref, UcmPathDecomposition decomposition, StringBuffer dot) {
        dot.append("subgraph " + AutoLayoutPreferences.CONTAINERPREFIX + ((URNmodelElement) ref).getId() + " {\n"); //$NON-NLS-1$ //$NON-NLS-2$
        dot.append("margin=\"12\";\n"); //$NON-NLS-1$

        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();) {
            Object child = it.next();
            if (child instanceof urncore.IURNContainerRef)
                cluster((urncore.IURNContainerRef) child, decomposition, dot);
        }
        for (Iterator<?> it = ref.getNodes().iterator(); it.hasNext();) {
            Object n = it.next();
            if (n instanceof PathNode && decomposition.getJunctions().contains(n))
                dot.append(node((PathNode) n));
        }
        dot.append("}\n"); //$NON-NLS-1$
    }

    private static String node(PathNode pn, boolean onSpine) {
        String group = onSpine ? ", group=\"spine\"" : ""; //$NON-NLS-1$ //$NON-NLS-2$
        return name(pn) + " [label=\"\", fixedsize=\"true\", width=\"0.35\", height=\"0.35\"" + group + "];\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String node(PathNode pn) {
        // label="" is not cosmetic. Without it a node keeps its name as a label, which cannot fit
        // in 0.35 inches, and dot writes "size too small for label" to stderr once per node -- 64KB
        // of it for a 1200-node map. Nothing drains that pipe, so dot blocks on a full stderr and
        // the layout hangs forever with no output and no error. See #30.
        return name(pn) + " [label=\"\", fixedsize=\"true\", width=\"0.35\", height=\"0.35\"];\n"; //$NON-NLS-1$
    }

    private static String name(PathNode pn) {
        return AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) pn).getId();
    }
}
