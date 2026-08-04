package seg.jUCMNav.importexport;

import java.util.Iterator;
import java.util.List;

import seg.jUCMNav.model.util.UcmPathDecomposition;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urncore.IURNContainerRef;
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
 * This emits the contracted graph instead: the junctions from {@link UcmPathDecomposition}, the
 * component clusters, and <b>one edge per chain</b> carrying a {@code minlen} proportional to how
 * many nodes have to fit along it. Graphviz then solves a small problem well, and the chain
 * interiors are placed afterwards by {@code ChainPlacement} along the route it chose.
 *
 * @author Claude
 */
public class ExportContractedDOT {

    /** Points per rank, roughly. Chains ask for room in these units via minlen. */
    private static final int NODES_PER_RANK = 1;

    /** Even an empty chain needs its two junctions kept apart. */
    private static final int MIN_RANK_SEPARATION = 1;

    private static int cheapTrick = 0;

    public static String convert(UCMmap map, UcmPathDecomposition decomposition) {
        StringBuffer dot = new StringBuffer();
        String id = ((URNmodelElement) map).getId();

        dot.append("digraph " + AutoLayoutPreferences.DIAGPREFIX + id + " {\n"); //$NON-NLS-1$ //$NON-NLS-2$
        dot.append("rankdir=\"" + AutoLayoutPreferences.getOrientation() + "\";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        dot.append("size=\"" + AutoLayoutPreferences.getWidth() + "," + AutoLayoutPreferences.getHeight() + "\";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Splines, because the routes are what the chains get distributed along. With polylines
        // the chain interiors would inherit the corners instead of curving around them.
        dot.append("splines=\"spline\";\nnodesep=\"0.6\";\nranksep=\"0.6\";\n"); //$NON-NLS-1$

        for (Iterator<?> it = map.getContRefs().iterator(); it.hasNext();) {
            IURNContainerRef ref = (IURNContainerRef) it.next();
            if (ref.getParent() == null)
                cluster(ref, decomposition, dot);
        }

        for (Iterator<PathNode> it = decomposition.getJunctions().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (pn.getContRef() == null)
                dot.append(node(pn));
        }

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            if (!(chain.getFrom() instanceof PathNode) || !(chain.getTo() instanceof PathNode))
                continue;

            int room = MIN_RANK_SEPARATION + chain.length() * NODES_PER_RANK;
            dot.append(name((PathNode) chain.getFrom()) + " -> " + name((PathNode) chain.getTo()) //$NON-NLS-1$
                    + " [minlen=\"" + room + "\"];\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        dot.append("}\n"); //$NON-NLS-1$
        return dot.toString();
    }

    private static void cluster(IURNContainerRef ref, UcmPathDecomposition decomposition, StringBuffer dot) {
        dot.append("subgraph " + AutoLayoutPreferences.CONTAINERPREFIX + ((URNmodelElement) ref).getId() + " {\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // A cluster with no node in it collapses to nothing, taking the component with it, so it
        // gets an invisible occupant sized like the component itself.
        dot.append("CheapTrick" + cheapTrick++ + " [style=\"invis\", width=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + ref.getWidth() / 72.0 + "\", height=\"" + ref.getHeight() / 72.0 + "\"];\n"); //$NON-NLS-1$ //$NON-NLS-2$

        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();) {
            Object child = it.next();
            if (child instanceof IURNContainerRef)
                cluster((IURNContainerRef) child, decomposition, dot);
        }

        for (Iterator<?> it = ref.getNodes().iterator(); it.hasNext();) {
            Object node = it.next();
            // Only junctions are placed by Graphviz, so only they belong in the cluster. A
            // pass-through inside a component is a junction by definition -- see
            // UcmPathDecomposition.isJunction -- precisely so that it stays in its component.
            if (node instanceof PathNode && decomposition.getJunctions().contains(node))
                dot.append(node((PathNode) node));
        }

        dot.append("}\n"); //$NON-NLS-1$
    }

    private static String node(PathNode pn) {
        return name(pn) + " [fixedsize=\"true\", width=\"0.35\", height=\"0.35\"];\n"; //$NON-NLS-1$
    }

    private static String name(PathNode pn) {
        return AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) pn).getId();
    }
}
