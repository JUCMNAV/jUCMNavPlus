package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ucm.map.NodeConnection;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urncore.IURNNode;

/**
 * A UCM map as what it actually is: a few long chains threading between a handful of junctions.
 *
 * <p>
 * Auto-layout used to hand Graphviz the whole map -- every empty point, every responsibility -- and
 * ask for a layered drawing of it. That is the wrong problem in two ways. A 200-node map is a
 * 200-node layout problem when it is really a 15-node one, because everything between two junctions
 * has no choice about where it goes; and a layered layout has no notion of the thing that decides
 * how a UCM looks, which is the smoothness of the curve through those nodes.
 *
 * <p>
 * So the map is split in two:
 *
 * <ul>
 * <li><b>junctions</b> -- the nodes where the topology genuinely branches or terminates: anything
 * with more than one predecessor or more than one successor, plus start and end points, stubs and
 * anything else that is not a plain pass-through. These are what Graphviz is asked to place;</li>
 * <li><b>chains</b> -- the maximal runs of pass-through nodes between two junctions. Their interior
 * is placed by {@link ChainPlacement}, along the curve rather than by rank.</li>
 * </ul>
 *
 * <p>
 * A pass-through node is one with exactly one predecessor and exactly one successor and no reason
 * to be anywhere in particular. Responsibilities and empty points are pass-throughs; forks, joins
 * and stubs are not, and neither is anything a component boundary makes significant -- see
 * {@link #isJunction}.
 *
 * <p>
 * Pure queries over the model, so this can be unit tested without a workbench and reasoned about
 * apart from the layout that consumes it.
 *
 * @author Claude
 */
public class UcmPathDecomposition {

    /** A run of pass-through nodes from one junction to another. */
    public static class Chain {
        private final IURNNode from, to;
        private final List<PathNode> interior;
        private final List<NodeConnection> connections;

        Chain(IURNNode from, IURNNode to, List<PathNode> interior, List<NodeConnection> connections) {
            this.from = from;
            this.to = to;
            this.interior = interior;
            this.connections = connections;
        }

        /** The junction the chain leaves. */
        public IURNNode getFrom() {
            return from;
        }

        /** The junction the chain reaches. */
        public IURNNode getTo() {
            return to;
        }

        /** The pass-through nodes between them, in order. Never contains a junction. */
        public List<PathNode> getInterior() {
            return Collections.unmodifiableList(interior);
        }

        /** Every connection the chain covers, from-junction to to-junction inclusive. */
        public List<NodeConnection> getConnections() {
            return Collections.unmodifiableList(connections);
        }

        /** How many nodes have to be spread along this chain. */
        public int length() {
            return interior.size();
        }
    }

    private final UCMmap map;
    private final Set<PathNode> junctions = new LinkedHashSet<PathNode>();
    private final List<Chain> chains = new ArrayList<Chain>();

    public UcmPathDecomposition(UCMmap map) {
        this.map = map;
        if (map == null)
            return;

        for (Iterator<?> it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (isJunction(pn))
                junctions.add(pn);
        }

        buildChains();
    }

    /**
     * Whether a node has to be placed in its own right.
     *
     * <p>
     * Anything that is not a plain one-in-one-out pass-through: branch points, merge points, and
     * the ends of the path. A node held by a component is <b>also</b> a junction even when it is a
     * pass-through, because which component performs it is a layout constraint -- letting it be
     * distributed freely along a chain would drag it out of its component.
     */
    public boolean isJunction(PathNode pn) {
        if (pn.getPred().size() != 1 || pn.getSucc().size() != 1)
            return true;

        return pn.getContRef() != null;
    }

    private void buildChains() {
        Set<NodeConnection> covered = new LinkedHashSet<NodeConnection>();

        for (Iterator<PathNode> it = junctions.iterator(); it.hasNext();) {
            PathNode junction = it.next();
            for (Iterator<?> succ = junction.getSucc().iterator(); succ.hasNext();) {
                NodeConnection first = (NodeConnection) succ.next();
                if (covered.contains(first))
                    continue;

                List<PathNode> interior = new ArrayList<PathNode>();
                List<NodeConnection> connections = new ArrayList<NodeConnection>();

                NodeConnection nc = first;
                IURNNode at = nc.getTarget();
                connections.add(nc);
                covered.add(nc);

                // Walk forward while the nodes have no say in where they go. Bounded by the
                // covered set, so a cycle of pass-throughs terminates instead of spinning.
                while (at instanceof PathNode && !junctions.contains(at)) {
                    interior.add((PathNode) at);
                    if (at.getSucc().isEmpty())
                        break;

                    nc = (NodeConnection) at.getSucc().get(0);
                    if (covered.contains(nc))
                        break;

                    connections.add(nc);
                    covered.add(nc);
                    at = nc.getTarget();
                }

                chains.add(new Chain(junction, at, interior, connections));
            }
        }
    }

    /** The nodes Graphviz is asked to place. */
    public Set<PathNode> getJunctions() {
        return Collections.unmodifiableSet(junctions);
    }

    /** The runs between them, whose interiors are placed along the curve. */
    public List<Chain> getChains() {
        return Collections.unmodifiableList(chains);
    }

    public UCMmap getMap() {
        return map;
    }

    /**
     * How much smaller the layout problem became -- junctions against total nodes. Diagnostic, and
     * the number that says whether contracting was worth doing on a given map.
     */
    public String describe() {
        return junctions.size() + " junctions, " + chains.size() + " chains, " //$NON-NLS-1$ //$NON-NLS-2$
                + (map == null ? 0 : map.getNodes().size()) + " nodes"; //$NON-NLS-1$
    }
}
