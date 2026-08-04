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
    private final Set<Chain> backEdges = new LinkedHashSet<Chain>();

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
        findBackEdges();
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

    /**
     * The chains that close a loop, found by depth-first search from the path's beginnings.
     *
     * <p>
     * A layered layout ranks every edge forward, so a loop's back edge drags its target below its
     * source and the whole drawing stretches to accommodate an ordering that cannot be satisfied.
     * Told to ignore these for ranking, Graphviz lays out the forward structure and routes the loop
     * around it -- which is how a UCM loop is drawn by hand, and measurably more compact.
     */
    private void findBackEdges() {
        Set<PathNode> exploring = new LinkedHashSet<PathNode>();
        Set<PathNode> done = new LinkedHashSet<PathNode>();

        // Start from the path's beginnings; anything left over belongs to a cycle nothing enters,
        // and is picked up by the second pass so no chain goes unclassified.
        for (Iterator<PathNode> it = junctions.iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (pn.getPred().isEmpty())
                walk(pn, exploring, done);
        }
        for (Iterator<PathNode> it = junctions.iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (!done.contains(pn))
                walk(pn, exploring, done);
        }
    }

    private void walk(PathNode at, Set<PathNode> exploring, Set<PathNode> done) {
        if (done.contains(at))
            return;

        exploring.add(at);
        for (int i = 0; i < chains.size(); i++) {
            Chain chain = chains.get(i);
            if (chain.getFrom() != at || !(chain.getTo() instanceof PathNode))
                continue;

            PathNode next = (PathNode) chain.getTo();
            if (exploring.contains(next))
                backEdges.add(chain); // reaches something we are still inside: a loop
            else
                walk(next, exploring, done);
        }
        exploring.remove(at);
        done.add(at);
    }

    /** Whether this chain closes a loop, and so should not constrain the ranking. */
    public boolean isBackEdge(Chain chain) {
        return backEdges.contains(chain);
    }

    /**
     * The longest run of junctions from a path beginning to a path end, ignoring loop back edges.
     *
     * <p>
     * This is the line a reader follows, and in a hand-drawn map it is straight with everything
     * else arranged around it. Told which junctions form it, Graphviz can be asked to keep them
     * colinear and bend the side branches instead; without it there is nothing in a contracted
     * graph to prefer one route through the map over another, and the drawing wanders.
     */
    public Set<PathNode> getSpine() {
        Set<PathNode> spine = new LinkedHashSet<PathNode>();
        List<PathNode> best = new ArrayList<PathNode>();

        for (Iterator<PathNode> it = junctions.iterator(); it.hasNext();) {
            PathNode start = it.next();
            if (!start.getPred().isEmpty())
                continue;

            List<PathNode> route = longestFrom(start, new LinkedHashSet<PathNode>());
            if (route.size() > best.size())
                best = route;
        }

        spine.addAll(best);
        return spine;
    }

    private List<PathNode> longestFrom(PathNode at, Set<PathNode> visiting) {
        List<PathNode> best = new ArrayList<PathNode>();
        if (!visiting.add(at))
            return best;

        for (int i = 0; i < chains.size(); i++) {
            Chain chain = chains.get(i);
            if (chain.getFrom() != at || backEdges.contains(chain) || !(chain.getTo() instanceof PathNode))
                continue;

            List<PathNode> onward = longestFrom((PathNode) chain.getTo(), visiting);
            if (onward.size() > best.size())
                best = onward;
        }

        visiting.remove(at);

        List<PathNode> route = new ArrayList<PathNode>();
        route.add(at);
        route.addAll(best);
        return route;
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
