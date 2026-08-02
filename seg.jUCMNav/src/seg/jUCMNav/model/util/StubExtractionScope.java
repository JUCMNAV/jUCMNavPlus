package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ucm.map.EndPoint;
import ucm.map.NodeConnection;
import ucm.map.PathNode;
import ucm.map.StartPoint;
import ucm.map.UCMmap;
import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * What "Refactor into Stub" is about to extract, worked out from the selection alone.
 *
 * <p>
 * Three things come out of a selection:
 *
 * <ul>
 * <li>the <b>scope</b>: the selected path nodes, plus every node lying between two of them.
 * Selecting just a fork and the join that recombines it therefore extracts the whole block --
 * anything else would leave the plug-in map missing the branches the stub is supposed to
 * represent;</li>
 * <li>the <b>boundary</b>: connections entering the scope from outside it, and connections
 * leaving it. These become the stub's in-paths and out-paths, one each, so their counts are right
 * by construction rather than by cleanup afterwards;</li>
 * <li>the <b>components</b>: container references holding any node in scope, which have to travel
 * with it;</li>
 * <li>the scope's <b>own extremities</b>: nodes nothing reaches, and nodes that reach nothing.
 * Well-formed maps have none of these -- start and end points are deliberately left out of the
 * scope, see {@link #leaveExtremitiesBehind} -- but generated models are not always well formed,
 * and an orphan node still has to be given a way in for the extraction to mean anything.</li>
 * </ul>
 *
 * <p>
 * This replaces the previous approach of deleting the selection, letting deletion incidentally
 * spawn start and end points wherever it severed a path, and then attaching whatever start and end
 * points turned out to be newer than the command. That produced one surplus in/out pair per
 * interior severing point -- extracting a fork/join block whose branches carry content gave a stub
 * with three in-paths and three out-paths where the boundary is one and one (#29) -- and it left
 * the stub-to-plug-in bindings to be re-derived afterwards by matching element names, because the
 * two sides had been built by unrelated mechanisms.
 *
 * <p>
 * Pure model queries: nothing here mutates, so it can be unit tested without a workbench and
 * reasoned about independently of the command that consumes it.
 *
 * @author Claude
 */
public class StubExtractionScope {

    private final Set<PathNode> scope;
    private final List<NodeConnection> inbound;
    private final List<NodeConnection> outbound;
    private final List<PathNode> ownStarts;
    private final List<PathNode> ownEnds;
    private final Set<IURNContainerRef> components;
    private final UCMmap map;

    /**
     * @param selection
     *            what the user selected; anything that is not a {@link PathNode} is ignored, so
     *            the caller can pass a raw selection through unfiltered
     */
    public StubExtractionScope(Collection<?> selection) {
        Set<PathNode> selected = new LinkedHashSet<PathNode>();
        for (Iterator<?> it = selection.iterator(); it.hasNext();) {
            Object o = it.next();
            if (o instanceof PathNode)
                selected.add((PathNode) o);
        }

        this.scope = close(selected);
        leaveExtremitiesBehind(this.scope);
        this.map = mapOf(this.scope);
        this.inbound = new ArrayList<NodeConnection>();
        this.outbound = new ArrayList<NodeConnection>();
        this.ownStarts = new ArrayList<PathNode>();
        this.ownEnds = new ArrayList<PathNode>();
        this.components = new LinkedHashSet<IURNContainerRef>();

        if (map != null)
            computeBoundary();
        computeExtremities();
        collectComponents();
    }

    /**
     * The selection plus everything between two selected nodes.
     *
     * A node counts as "between" when it is reachable from a selected node and can itself reach a
     * selected node -- the two halves of lying on a path from one to another.
     */
    private Set<PathNode> close(Set<PathNode> selected) {
        Set<PathNode> closed = new LinkedHashSet<PathNode>(selected);
        if (selected.size() < 2)
            return closed;

        Set<PathNode> downstream = new HashSet<PathNode>();
        for (Iterator<PathNode> it = selected.iterator(); it.hasNext();)
            walk(it.next(), downstream, true);

        for (Iterator<PathNode> it = downstream.iterator(); it.hasNext();) {
            PathNode candidate = it.next();
            if (closed.contains(candidate))
                continue;

            // Does any selected node lie downstream of this candidate? If so it sits between two
            // selected nodes and belongs in the scope.
            Set<PathNode> onward = new HashSet<PathNode>();
            walk(candidate, onward, true);
            if (!Collections.disjoint(onward, selected))
                closed.add(candidate);
        }
        return closed;
    }

    /**
     * A map's start and end points stay on the map they belong to, even when the selection covers
     * them.
     *
     * <p>
     * They are not ordinary path nodes: they are the map's interface, and a stub replaces a
     * <i>body</i>, not an entry. Moving a start point into the plug-in map breaks two things at
     * once. The parent map is left with no way in -- the reported symptom, a stub with no in-path
     * and a path with no beginning. And any scenario definition anchored on that start point then
     * begins its traversal <i>inside</i> the plug-in map, which it was never entered through, so
     * when it reaches the far end there is no stub to return from and everything downstream on the
     * parent map goes unvisited.
     *
     * <p>
     * Leaving them behind costs nothing and settles both. The connection from a retained start
     * point into the scope is an ordinary inbound crossing, so it becomes a stub in-path by the
     * same rule as everything else, and the plug-in map gets a fresh start point of its own to
     * continue from. The original keeps its name, its preconditions and the scenarios that
     * reference it, on the map where they still mean what they meant.
     */
    private void leaveExtremitiesBehind(Set<PathNode> closed) {
        for (Iterator<PathNode> it = closed.iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (pn instanceof StartPoint || pn instanceof EndPoint)
                it.remove();
        }
    }

    private void walk(IURNNode from, Set<PathNode> seen, boolean forward) {
        Collection<?> edges = forward ? from.getSucc() : from.getPred();
        for (Iterator<?> it = edges.iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            IURNNode next = forward ? nc.getTarget() : nc.getSource();
            if (next instanceof PathNode && seen.add((PathNode) next))
                walk(next, seen, forward);
        }
    }

    private UCMmap mapOf(Set<PathNode> nodes) {
        for (Iterator<PathNode> it = nodes.iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (pn.getDiagram() instanceof UCMmap)
                return (UCMmap) pn.getDiagram();
        }
        return null;
    }

    private void computeBoundary() {
        for (Iterator<?> it = map.getConnections().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            boolean sourceIn = nc.getSource() instanceof PathNode && scope.contains(nc.getSource());
            boolean targetIn = nc.getTarget() instanceof PathNode && scope.contains(nc.getTarget());

            if (targetIn && !sourceIn)
                inbound.add(nc);
            else if (sourceIn && !targetIn)
                outbound.add(nc);
        }
    }

    /**
     * The scope's own beginnings and ends: nodes with nothing feeding them, and nodes with nothing
     * following them.
     *
     * A node with any incoming connection is already accounted for -- the connection is either
     * interior, in which case something in scope reaches it, or inbound, in which case it gets a
     * stub in-path. A node with none is a start point (or, in a malformed map, an orphan), and it
     * is the whole path's way in. Same argument mirrored for outgoing.
     */
    private void computeExtremities() {
        for (Iterator<PathNode> it = scope.iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (pn.getPred().isEmpty())
                ownStarts.add(pn);
            if (pn.getSucc().isEmpty())
                ownEnds.add(pn);
        }
    }

    private void collectComponents() {
        for (Iterator<PathNode> it = scope.iterator(); it.hasNext();) {
            IURNContainerRef ref = it.next().getContRef();
            while (ref != null) {
                components.add(ref);
                ref = ref.getParent();
            }
        }
    }

    /** The nodes to move onto the plug-in map: the selection plus everything between. */
    public Set<PathNode> getScope() {
        return Collections.unmodifiableSet(scope);
    }

    /** Connections entering the scope; one stub in-path each. */
    public List<NodeConnection> getInbound() {
        return Collections.unmodifiableList(inbound);
    }

    /** Connections leaving the scope; one stub out-path each. */
    public List<NodeConnection> getOutbound() {
        return Collections.unmodifiableList(outbound);
    }

    /**
     * Nodes in scope that nothing reaches -- the start points the selection swallowed. Each one
     * needs a stub in-path anchored on the parent map, or the parent map is left with no way in.
     */
    public List<PathNode> getOwnStarts() {
        return Collections.unmodifiableList(ownStarts);
    }

    /** Nodes in scope that reach nothing -- the end points the selection swallowed. */
    public List<PathNode> getOwnEnds() {
        return Collections.unmodifiableList(ownEnds);
    }

    /** Container references holding a node in scope, innermost first, with their ancestors. */
    public Set<IURNContainerRef> getComponents() {
        return Collections.unmodifiableSet(components);
    }

    /** The map the selection came from, or null if the selection held no diagram node. */
    public UCMmap getMap() {
        return map;
    }

    /** Whether there is anything to extract. */
    public boolean isEmpty() {
        return scope.isEmpty() || map == null;
    }
}
