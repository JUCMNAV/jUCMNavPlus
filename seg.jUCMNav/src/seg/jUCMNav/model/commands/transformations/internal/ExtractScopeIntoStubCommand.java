package seg.jUCMNav.model.commands.transformations.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.util.ParentFinder;
import seg.jUCMNav.model.util.StubExtractionScope;
import ucm.map.ComponentRef;
import ucm.map.EndPoint;
import ucm.map.NodeConnection;
import ucm.map.PathNode;
import ucm.map.StartPoint;
import ucm.map.Stub;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.Condition;
import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * Moves a {@link StubExtractionScope} onto a plug-in map and puts a stub in its place.
 *
 * <p>
 * The whole transformation, done from the boundary rather than from the wreckage of a deletion:
 *
 * <ol>
 * <li>every node in scope, and every connection with both ends in scope, moves to the plug-in map;</li>
 * <li>each inbound boundary connection is retargeted to the stub, and gains a StartPoint on the
 * plug-in map feeding the node it used to reach;</li>
 * <li>each outbound boundary connection is re-sourced from the stub, and gains an EndPoint on the
 * plug-in map fed by the node it used to leave;</li>
 * <li>each start point the scope swallowed is anchored by a fresh StartPoint on the parent map
 * feeding the stub, and each swallowed end point by a fresh EndPoint the stub feeds.</li>
 * </ol>
 *
 * The stub therefore ends up with exactly one in-path per inbound connection and one out-path per
 * outbound one -- by construction, not by cleaning up afterwards -- and the pairing between a stub
 * path and its plug-in endpoint is known here rather than re-derived later by matching names.
 *
 * <p>
 * Step 4 is what keeps both maps whole when the selection reaches an extremity of the path. Nothing
 * enters a scope containing the map's start point, so the boundary is empty on that side and the
 * first three steps alone would leave the parent map with a stub that has no in-path and no start
 * point anywhere -- a path with no beginning -- while the plug-in map held a start point bound to
 * nothing. Anchoring restores both: traversal begins on the parent map, enters the stub, and
 * continues from the original start point, which keeps its name, its preconditions and any scenario
 * that references it.
 *
 * <p>
 * Nodes are moved, not copied, so ids, responsibility definitions, metadata and any other identity
 * survive the extraction.
 *
 * @author Claude
 */
public class ExtractScopeIntoStubCommand extends Command {

    private final StubExtractionScope scope;
    private final UCMmap parentMap;
    private final UCMmap pluginMap;
    private final URNspec urn;
    private final Stub stub;
    private final int stubX;
    private final int stubY;

    /** Stub path (a connection on the parent map) -> the plug-in endpoint it is bound to. */
    private final Map<NodeConnection, StartPoint> entryPoints = new HashMap<NodeConnection, StartPoint>();
    private final Map<NodeConnection, EndPoint> exitPoints = new HashMap<NodeConnection, EndPoint>();
    private final List<NodeConnection> entryOrder = new ArrayList<NodeConnection>();
    private final List<NodeConnection> exitOrder = new ArrayList<NodeConnection>();

    /** Everything needed to put the model back. */
    private final List<NodeConnection> movedConnections = new ArrayList<NodeConnection>();
    private final Map<NodeConnection, IURNNode> originalTargets = new HashMap<NodeConnection, IURNNode>();
    private final Map<NodeConnection, IURNNode> originalSources = new HashMap<NodeConnection, IURNNode>();
    private final List<NodeConnection> createdConnections = new ArrayList<NodeConnection>();
    private final List<PathNode> createdNodes = new ArrayList<PathNode>();
    private final Map<NodeConnection, Condition> movedGuards = new HashMap<NodeConnection, Condition>();
    private final Map<IURNContainerRef, IURNContainerRef> movedComponents = new HashMap<IURNContainerRef, IURNContainerRef>();
    private final Map<PathNode, IURNContainerRef> originalContainers = new HashMap<PathNode, IURNContainerRef>();
    private IURNContainerRef stubContainerBefore;

    public ExtractScopeIntoStubCommand(StubExtractionScope scope, UCMmap pluginMap, Stub stub, int x, int y) {
        this.scope = scope;
        this.parentMap = scope.getMap();
        this.pluginMap = pluginMap;
        this.stub = stub;
        this.stubX = x;
        this.stubY = y;
        this.urn = parentMap == null ? null : parentMap.getUrndefinition().getUrnspec();
    }

    public boolean canExecute() {
        return scope != null && !scope.isEmpty() && pluginMap != null && stub != null;
    }

    public void execute() {
        // The stub takes the place of what is leaving.
        stub.setX(stubX);
        stub.setY(stubY);
        stub.setDiagram(parentMap);

        moveComponents();
        moveNodes();
        moveInternalConnections();
        rewireBoundary();
        anchorOwnExtremities();

        // Containment follows geometry in this model -- the suite asserts
        // ParentFinder.getPossibleParent(node) == node.getContRef() for every node on every map --
        // so anything placed or relocated has to have its container recomputed. Done last, once
        // every node and component sits where it finally belongs.
        reparent(stub);
        for (Iterator<PathNode> it = scope.getScope().iterator(); it.hasNext();)
            reparent(it.next());
        for (Iterator<PathNode> it = createdNodes.iterator(); it.hasNext();)
            reparent(it.next());
    }

    private void reparent(PathNode pn) {
        if (!originalContainers.containsKey(pn))
            originalContainers.put(pn, pn.getContRef());
        pn.setContRef(ParentFinder.getPossibleParent(pn));
    }

    public void redo() {
        execute();
    }

    public void undo() {
        // Reverse order of execute().
        for (Iterator<NodeConnection> it = createdConnections.iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setSource(null);
            nc.setTarget(null);
            nc.setDiagram(null);
        }
        createdConnections.clear();

        // Boundary connections that were retargeted onto the stub go back to what they used to
        // reach. Anchor connections are not in these maps -- they were created outright, and the
        // sweep above has already detached them.
        for (Iterator<NodeConnection> it = originalTargets.keySet().iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setTarget(originalTargets.get(nc));
        }
        for (Iterator<NodeConnection> it = originalSources.keySet().iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setSource(originalSources.get(nc));
        }
        originalTargets.clear();
        originalSources.clear();

        for (Iterator<NodeConnection> it = movedGuards.keySet().iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setCondition(movedGuards.get(nc));
        }
        movedGuards.clear();

        for (Iterator<PathNode> it = createdNodes.iterator(); it.hasNext();)
            it.next().setDiagram(null);
        createdNodes.clear();

        entryPoints.clear();
        exitPoints.clear();
        entryOrder.clear();
        exitOrder.clear();

        for (Iterator<PathNode> it = originalContainers.keySet().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            pn.setContRef(originalContainers.get(pn));
        }
        originalContainers.clear();

        for (Iterator<NodeConnection> it = movedConnections.iterator(); it.hasNext();)
            it.next().setDiagram(parentMap);
        movedConnections.clear();

        for (Iterator<PathNode> it = scope.getScope().iterator(); it.hasNext();)
            it.next().setDiagram(parentMap);

        for (Iterator<IURNContainerRef> it = movedComponents.keySet().iterator(); it.hasNext();)
            ((ComponentRef) it.next()).setDiagram(parentMap);
        movedComponents.clear();

        stub.setDiagram(null);
    }

    /**
     * Container references holding scoped nodes travel with them.
     *
     * Only wholly-contained components move: one that also holds nodes staying behind cannot be in
     * two maps at once, and splitting it means deciding what the two halves mean. Those nodes keep
     * their component on the parent map and simply arrive on the plug-in map uncontained, which is
     * lossy but visible, rather than silently wrong. Replication for the partial case is left for
     * a follow-up.
     */
    private void moveComponents() {
        for (Iterator<IURNContainerRef> it = scope.getComponents().iterator(); it.hasNext();) {
            IURNContainerRef ref = it.next();
            if (!(ref instanceof ComponentRef))
                continue;

            boolean wholly = true;
            for (Iterator<?> nodes = ref.getNodes().iterator(); nodes.hasNext();) {
                Object node = nodes.next();
                if (node instanceof PathNode && !scope.getScope().contains(node))
                    wholly = false;
            }
            if (wholly) {
                movedComponents.put(ref, ref);
                ((ComponentRef) ref).setDiagram(pluginMap);
            }
        }
    }

    private void moveNodes() {
        for (Iterator<PathNode> it = scope.getScope().iterator(); it.hasNext();)
            it.next().setDiagram(pluginMap);
    }

    /** A connection with both ends in scope is interior and travels with them. */
    private void moveInternalConnections() {
        Set<PathNode> inScope = scope.getScope();
        List<NodeConnection> interior = new ArrayList<NodeConnection>();
        for (Iterator<?> it = new ArrayList<Object>(parentMap.getConnections()).iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (inScope.contains(nc.getSource()) && inScope.contains(nc.getTarget()))
                interior.add(nc);
        }
        for (Iterator<NodeConnection> it = interior.iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setDiagram(pluginMap);
            movedConnections.add(nc);
        }
    }

    /**
     * One stub in-path per inbound connection, one out-path per outbound one, each paired with the
     * plug-in endpoint that continues it.
     */
    private void rewireBoundary() {
        for (Iterator<NodeConnection> it = scope.getInbound().iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            IURNNode wasReaching = nc.getTarget();

            StartPoint entry = (StartPoint) ModelCreationFactory.getNewObject(urn, StartPoint.class);
            entry.setX(wasReaching.getX() - 40);
            entry.setY(wasReaching.getY());
            entry.setDiagram(pluginMap);
            createdNodes.add(entry);
            createConnection(entry, wasReaching, pluginMap);

            originalTargets.put(nc, wasReaching);
            nc.setTarget(stub);

            entryPoints.put(nc, entry);
            entryOrder.add(nc);
        }

        for (Iterator<NodeConnection> it = scope.getOutbound().iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            IURNNode wasLeaving = nc.getSource();

            EndPoint exit = (EndPoint) ModelCreationFactory.getNewObject(urn, EndPoint.class);
            exit.setX(wasLeaving.getX() + 40);
            exit.setY(wasLeaving.getY());
            exit.setDiagram(pluginMap);
            createdNodes.add(exit);
            NodeConnection carry = createConnection(wasLeaving, exit, pluginMap);

            // A branch guard belongs with the fork that reads it, and that fork has just moved onto
            // the plug-in map. Left behind on the parent connection it guards nothing there -- the
            // choice between the stub's out-paths is made by the binding, not by a condition -- and
            // the fork inside the plug-in is left with branches that all evaluate to true, so the
            // traversal reports multiple alternatives and picks one to stay deterministic. That is
            // a silently different scenario, not a cosmetic warning.
            Condition guard = nc.getCondition();
            if (guard != null) {
                movedGuards.put(nc, guard);
                carry.setCondition(guard);
            }

            originalSources.put(nc, wasLeaving);
            nc.setSource(stub);

            exitPoints.put(nc, exit);
            exitOrder.add(nc);
        }
    }

    /**
     * Gives the stub a path for each start and end point the scope swallowed.
     *
     * <p>
     * A start point is not a boundary crossing -- nothing enters the scope through it -- so the
     * boundary rule alone says nothing about it, and the first three steps would move it away and
     * leave the parent map with a stub nothing feeds and no start point at all. What the parent map
     * lost is exactly one way in, so it gets exactly one back: a fresh start point leading into the
     * stub, bound to the original on the plug-in map. The path is unchanged end to end, and the
     * original keeps its name, preconditions and scenario references.
     *
     * <p>
     * Since the only nodes with nothing feeding them are start points (or, in a malformed map,
     * orphans, which are given a plug-in start point of their own so the extraction stays wireable),
     * this also settles the general guarantee: any non-empty scope leaves the stub with at least one
     * in-path and one out-path. Either something crossed the boundary, or the scope owns the
     * extremity that explains why nothing did.
     */
    private void anchorOwnExtremities() {
        int i = 0;
        for (Iterator<PathNode> it = scope.getOwnStarts().iterator(); it.hasNext();) {
            PathNode head = it.next();

            StartPoint pluginStart;
            if (head instanceof StartPoint) {
                pluginStart = (StartPoint) head;
            } else {
                pluginStart = (StartPoint) ModelCreationFactory.getNewObject(urn, StartPoint.class);
                pluginStart.setX(head.getX() - 40);
                pluginStart.setY(head.getY());
                pluginStart.setDiagram(pluginMap);
                createdNodes.add(pluginStart);
                createConnection(pluginStart, head, pluginMap);
            }

            StartPoint anchor = (StartPoint) ModelCreationFactory.getNewObject(urn, StartPoint.class);
            anchor.setX(stubX - 60);
            anchor.setY(stubY + i * 40);
            anchor.setDiagram(parentMap);
            createdNodes.add(anchor);

            NodeConnection lead = createConnection(anchor, stub, parentMap);
            entryPoints.put(lead, pluginStart);
            entryOrder.add(lead);
            i++;
        }

        i = 0;
        for (Iterator<PathNode> it = scope.getOwnEnds().iterator(); it.hasNext();) {
            PathNode tail = it.next();

            EndPoint pluginEnd;
            if (tail instanceof EndPoint) {
                pluginEnd = (EndPoint) tail;
            } else {
                pluginEnd = (EndPoint) ModelCreationFactory.getNewObject(urn, EndPoint.class);
                pluginEnd.setX(tail.getX() + 40);
                pluginEnd.setY(tail.getY());
                pluginEnd.setDiagram(pluginMap);
                createdNodes.add(pluginEnd);
                createConnection(tail, pluginEnd, pluginMap);
            }

            EndPoint anchor = (EndPoint) ModelCreationFactory.getNewObject(urn, EndPoint.class);
            anchor.setX(stubX + 60);
            anchor.setY(stubY + i * 40);
            anchor.setDiagram(parentMap);
            createdNodes.add(anchor);

            NodeConnection trail = createConnection(stub, anchor, parentMap);
            exitPoints.put(trail, pluginEnd);
            exitOrder.add(trail);
            i++;
        }
    }

    private NodeConnection createConnection(IURNNode from, IURNNode to, UCMmap on) {
        NodeConnection nc = (NodeConnection) ModelCreationFactory.getNewObject(urn, NodeConnection.class);
        nc.setSource(from);
        nc.setTarget(to);
        nc.setDiagram(on);
        createdConnections.add(nc);
        return nc;
    }

    /** The stub's in-paths: retargeted inbound connections first, then anchored start points. */
    public List<NodeConnection> getEntryConnections() {
        return entryOrder;
    }

    /** The stub's out-paths: re-sourced outbound connections first, then anchored end points. */
    public List<NodeConnection> getExitConnections() {
        return exitOrder;
    }

    /** The plug-in start point the given in-path continues into. */
    public StartPoint getEntryPoint(NodeConnection inPath) {
        return entryPoints.get(inPath);
    }

    /** The plug-in end point the given out-path continues from. */
    public EndPoint getExitPoint(NodeConnection outPath) {
        return exitPoints.get(outPath);
    }

    /** For diagnostics: everything this command will take off the parent map. */
    public Set<PathNode> getMovedNodes() {
        return new HashSet<PathNode>(scope.getScope());
    }
}
