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
 * plug-in map fed by the node it used to leave.</li>
 * </ol>
 *
 * The stub therefore ends up with exactly one in-path per inbound connection and one out-path per
 * outbound one -- by construction, not by cleaning up afterwards -- and the pairing between a stub
 * path and its plug-in endpoint is known here rather than re-derived later by matching names.
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

    /** Boundary connection -> the plug-in endpoint created for it. Insertion-ordered. */
    private final Map<NodeConnection, StartPoint> entryPoints = new HashMap<NodeConnection, StartPoint>();
    private final Map<NodeConnection, EndPoint> exitPoints = new HashMap<NodeConnection, EndPoint>();
    private final List<NodeConnection> entryOrder = new ArrayList<NodeConnection>();
    private final List<NodeConnection> exitOrder = new ArrayList<NodeConnection>();

    /** Everything needed to put the model back. */
    private final List<NodeConnection> movedConnections = new ArrayList<NodeConnection>();
    private final Map<NodeConnection, IURNNode> originalTargets = new HashMap<NodeConnection, IURNNode>();
    private final Map<NodeConnection, IURNNode> originalSources = new HashMap<NodeConnection, IURNNode>();
    private final List<NodeConnection> createdConnections = new ArrayList<NodeConnection>();
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

        // Containment follows geometry in this model -- the suite asserts
        // ParentFinder.getPossibleParent(node) == node.getContRef() for every node on every map --
        // so anything placed or relocated has to have its container recomputed. Done last, once
        // every node and component sits where it finally belongs.
        reparent(stub);
        for (Iterator<PathNode> it = scope.getScope().iterator(); it.hasNext();)
            reparent(it.next());
        for (Iterator<NodeConnection> it = entryOrder.iterator(); it.hasNext();)
            reparent(entryPoints.get(it.next()));
        for (Iterator<NodeConnection> it = exitOrder.iterator(); it.hasNext();)
            reparent(exitPoints.get(it.next()));
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

        for (Iterator<NodeConnection> it = entryOrder.iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setTarget(originalTargets.get(nc));
            entryPoints.get(nc).setDiagram(null);
        }
        for (Iterator<NodeConnection> it = exitOrder.iterator(); it.hasNext();) {
            NodeConnection nc = it.next();
            nc.setSource(originalSources.get(nc));
            exitPoints.get(nc).setDiagram(null);
        }
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

            NodeConnection carry = (NodeConnection) ModelCreationFactory.getNewObject(urn, NodeConnection.class);
            carry.setSource(entry);
            carry.setTarget(wasReaching);
            carry.setDiagram(pluginMap);
            createdConnections.add(carry);

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

            NodeConnection carry = (NodeConnection) ModelCreationFactory.getNewObject(urn, NodeConnection.class);
            carry.setSource(wasLeaving);
            carry.setTarget(exit);
            carry.setDiagram(pluginMap);
            createdConnections.add(carry);

            originalSources.put(nc, wasLeaving);
            nc.setSource(stub);

            exitPoints.put(nc, exit);
            exitOrder.add(nc);
        }
    }

    /** Inbound boundary connections, in the order their plug-in start points were made. */
    public List<NodeConnection> getEntryConnections() {
        return entryOrder;
    }

    /** Outbound boundary connections, in the order their plug-in end points were made. */
    public List<NodeConnection> getExitConnections() {
        return exitOrder;
    }

    /** The plug-in start point that continues the given inbound connection. */
    public StartPoint getEntryPoint(NodeConnection inbound) {
        return entryPoints.get(inbound);
    }

    /** The plug-in end point that the given outbound connection continues from. */
    public EndPoint getExitPoint(NodeConnection outbound) {
        return exitPoints.get(outbound);
    }

    /** For diagnostics: everything this command will take off the parent map. */
    public Set<PathNode> getMovedNodes() {
        return new HashSet<PathNode>(scope.getScope());
    }
}
