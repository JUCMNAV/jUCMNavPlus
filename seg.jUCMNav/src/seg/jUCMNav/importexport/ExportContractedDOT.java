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
 * <b>Components are deliberately not clusters.</b> That reads like an omission and is the single
 * biggest thing separating a tangle from a diagram. A Graphviz cluster is a tight box that its
 * members are packed into, but a UCM component is a <i>band</i>: in the reporter's own model the
 * Triage Team spans 1681 pixels, from the second node of the path to the second-to-last, because
 * the team takes part at several points. Force those members into a box and the path has to leave
 * and re-enter it at every one of them, which is exactly the crossing tangle that came out.
 * Rendered side by side, dropping the clusters is the difference between a knot and a clean
 * left-to-right flow with a symmetric fork/join. Nothing is lost by it either, because a
 * component's rectangle is derived from where its nodes land rather than dictated to Graphviz --
 * so it becomes the band it should have been.
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

        for (Iterator<PathNode> it = decomposition.getJunctions().iterator(); it.hasNext();)
            dot.append(node(it.next()));

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
