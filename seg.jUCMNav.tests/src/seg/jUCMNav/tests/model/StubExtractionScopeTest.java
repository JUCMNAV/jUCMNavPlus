package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.model.util.StubExtractionScope;
import ucm.map.ComponentRef;
import ucm.map.EmptyPoint;
import ucm.map.EndPoint;
import ucm.map.MapFactory;
import ucm.map.NodeConnection;
import ucm.map.PathNode;
import ucm.map.StartPoint;
import ucm.map.UCMmap;
import urn.URNspec;
import urn.UrnFactory;
import urncore.IURNContainerRef;
import urncore.IURNDiagram;
import urncore.URNmodelElement;
import urncore.UrncoreFactory;

/**
 * {@link StubExtractionScope} decides what Refactor into Stub extracts, so almost all of the
 * correctness of that operation lives here rather than in the command. These are true unit tests:
 * the class only queries the model, so no workbench is needed and the sample loads straight
 * through EMF.
 *
 * <p>
 * The sample is the model from #29, {@code ExtractStub.jucm}:
 *
 * <pre>
 * Start14 -&gt; EP15 -&gt; OrFork36 -+-&gt; EP23 -&gt; RespB58 -----------+-&gt; OrJoin48 -&gt; RespC60 -&gt; End16
 *                              +-&gt; EP37 -&gt; RespA56 -&gt; EP49 ---+
 * </pre>
 *
 * <p>
 * Beyond the reported cases there is an exhaustive pass: every one of the 2^11 subsets of the
 * sample's nodes is fed through the class and checked against the invariants the command relies
 * on. That is cheap here -- pure queries over eleven nodes -- and it covers shapes nobody would
 * think to write by hand, which is exactly where this operation historically went wrong.
 *
 * @author Claude
 */
public class StubExtractionScopeTest {

    private URNspec sample;
    private UCMmap sampleMap;

    @Before
    public void loadSample() throws Exception {
        ucm.map.impl.MapPackageImpl.init();
        ResourceSet rs = new ResourceSetImpl();
        rs.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jucm", new XMIResourceFactoryImpl()); //$NON-NLS-1$
        Resource r = rs.createResource(URI.createURI("sample.jucm")); //$NON-NLS-1$
        r.load(StubExtractionScopeTest.class.getResourceAsStream("/seg/jUCMNav/tests/commands/ExtractStub.jucm"), //$NON-NLS-1$
                new HashMap<Object, Object>());

        sample = (URNspec) r.getContents().get(0);
        for (Iterator it = sample.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                sampleMap = (UCMmap) d;
        }
        assertTrue("the sample should hold a UCM map", sampleMap != null); //$NON-NLS-1$
        // 11 nodes: Start14, EP15, End16, EP23, OrFork36, EP37, OrJoin48, EP49, RespA56, RespB58,
        // RespC60. Asserted so a changed sample surfaces here rather than as puzzling failures.
        assertEquals("unexpected sample shape", 11, sampleMap.getNodes().size()); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------ helpers

    private PathNode n(String id) {
        for (Iterator it = sampleMap.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (id.equals(((URNmodelElement) pn).getId()))
                return pn;
        }
        throw new IllegalArgumentException("no node " + id); //$NON-NLS-1$
    }

    private Set<PathNode> sel(String... ids) {
        Set<PathNode> s = new LinkedHashSet<PathNode>();
        for (int i = 0; i < ids.length; i++)
            s.add(n(ids[i]));
        return s;
    }

    private Set<String> idsOf(Set<PathNode> nodes) {
        Set<String> ids = new HashSet<String>();
        for (Iterator<PathNode> it = nodes.iterator(); it.hasNext();)
            ids.add(((URNmodelElement) it.next()).getId());
        return ids;
    }

    private String shape(StubExtractionScope scope) {
        return "scope=" + scope.getScope().size() + " in=" + scope.getInbound().size() //$NON-NLS-1$ //$NON-NLS-2$
                + " out=" + scope.getOutbound().size(); //$NON-NLS-1$
    }

    // ------------------------------------------------------ the reported cases

    /** Selecting from before the fork to after the join: a single entry and a single exit. */
    @Test
    public void wholeForkJoinBlockHasOneEntryAndOneExit() {
        StubExtractionScope scope = new StubExtractionScope(sel("36", "23", "37", "56", "58", "49", "48")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

        assertEquals("whole block", "scope=7 in=1 out=1", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the entry should be the connection into the fork", //$NON-NLS-1$
                n("36"), scope.getInbound().get(0).getTarget()); //$NON-NLS-1$
        assertEquals("the exit should leave the join", n("48"), scope.getOutbound().get(0).getSource()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The behaviour change agreed for the rewrite: selecting only the fork and the join must pull
     * in everything between them, so the plug-in map keeps the branches the stub represents.
     * Extracting {36, 48} alone would leave a stub claiming to stand for a block whose contents
     * stayed behind -- the "plug-in map loses equivalence" half of #29.
     */
    @Test
    public void forkAndJoinAloneCloseOverTheWholeBlock() {
        StubExtractionScope scope = new StubExtractionScope(sel("36", "48")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("fork+join closes to the block", "scope=7 in=1 out=1", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the closure should be exactly the block", //$NON-NLS-1$
                new HashSet<String>(Arrays.asList("36", "23", "37", "56", "58", "49", "48")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                idsOf(scope.getScope()));
    }

    /** Both reported selections must describe the same extraction. */
    @Test
    public void bothReportedSelectionsAgree() {
        StubExtractionScope whole = new StubExtractionScope(sel("36", "23", "37", "56", "58", "49", "48")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        StubExtractionScope ends = new StubExtractionScope(sel("36", "48")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("selecting the block and selecting only its extremities should agree", //$NON-NLS-1$
                idsOf(whole.getScope()), idsOf(ends.getScope()));
    }

    /** A lone responsibility mid-path: one in, one out, nothing dragged along. */
    @Test
    public void singleResponsibilityIsItsOwnScope() {
        StubExtractionScope scope = new StubExtractionScope(sel("60")); //$NON-NLS-1$
        assertEquals("single node", "scope=1 in=1 out=1", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -------------------------------------------------------- closure semantics

    /**
     * Responsibilities on parallel branches are not between one another, so neither drags in the
     * fork, the join, or the other branch. The result is two disconnected fragments with two
     * entries and two exits -- unusual, but exactly what the boundary rule says, and the stub that
     * comes out of it is honest about the shape.
     */
    @Test
    public void parallelBranchNodesDoNotCloseOverEachOther() {
        StubExtractionScope scope = new StubExtractionScope(sel("56", "58")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("parallel responsibilities", "scope=2 in=2 out=2", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("neither branch should pull in the other", //$NON-NLS-1$
                new HashSet<String>(Arrays.asList("56", "58")), idsOf(scope.getScope())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Selecting a node and something downstream of it pulls in what lies between. */
    @Test
    public void closureFillsInThePathBetweenTwoSelectedNodes() {
        StubExtractionScope scope = new StubExtractionScope(sel("37", "49")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("EP37 .. EP49 should absorb RespA56", //$NON-NLS-1$
                new HashSet<String>(Arrays.asList("37", "56", "49")), idsOf(scope.getScope())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a straight run", "scope=3 in=1 out=1", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Closing an already-closed scope must change nothing. */
    @Test
    public void closureIsIdempotentOnEveryReportedSelection() {
        String[][] selections = { { "36", "48" }, { "36", "23", "37", "56", "58", "49", "48" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
                { "14", "16" }, { "56", "58" }, { "37", "49" } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        for (int i = 0; i < selections.length; i++) {
            StubExtractionScope once = new StubExtractionScope(sel(selections[i]));
            StubExtractionScope twice = new StubExtractionScope(new ArrayList<PathNode>(once.getScope()));
            assertEquals("closing twice should change nothing for " + Arrays.toString(selections[i]), //$NON-NLS-1$
                    idsOf(once.getScope()), idsOf(twice.getScope()));
        }
    }

    /** Start to end absorbs the entire path, leaving nothing outside. */
    @Test
    public void startToEndClosesOverTheWholeMap() {
        StubExtractionScope scope = new StubExtractionScope(sel("14", "16")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("start..end should be the whole map", //$NON-NLS-1$
                sampleMap.getNodes().size(), scope.getScope().size());
        assertEquals("nothing enters or leaves a whole-map scope", //$NON-NLS-1$
                "scope=" + sampleMap.getNodes().size() + " in=0 out=0", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ------------------------------------------------------- boundary semantics

    @Test
    public void selectingEverythingLeavesNoBoundary() {
        StubExtractionScope scope = new StubExtractionScope(new ArrayList<Object>(sampleMap.getNodes()));
        assertEquals("a scope covering the map has no boundary", //$NON-NLS-1$
                "scope=" + sampleMap.getNodes().size() + " in=0 out=0", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The start point has no predecessor, so it contributes an exit but no entry. */
    @Test
    public void startPointAloneHasNoEntry() {
        assertEquals("start point", "scope=1 in=0 out=1", shape(new StubExtractionScope(sel("14")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** And the end point the mirror image. */
    @Test
    public void endPointAloneHasNoExit() {
        assertEquals("end point", "scope=1 in=1 out=0", shape(new StubExtractionScope(sel("16")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The fork's two outgoing branches are two separate exits, not one. */
    @Test
    public void aForkAloneHasOneEntryAndTwoExits() {
        assertEquals("fork alone", "scope=1 in=1 out=2", shape(new StubExtractionScope(sel("36")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** And the join two entries and one exit. */
    @Test
    public void aJoinAloneHasTwoEntriesAndOneExit() {
        assertEquals("join alone", "scope=1 in=2 out=1", shape(new StubExtractionScope(sel("48")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // -------------------------------------------------------------- robustness

    @Test
    public void anEmptySelectionYieldsNothing() {
        StubExtractionScope scope = new StubExtractionScope(Collections.emptyList());
        assertTrue("an empty selection should report empty", scope.isEmpty()); //$NON-NLS-1$
        assertEquals("empty", "scope=0 in=0 out=0", shape(scope)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A raw viewer selection carries all sorts of things; only path nodes matter. */
    @Test
    public void nonPathNodesInTheSelectionAreIgnored() {
        List<Object> mixed = new ArrayList<Object>();
        mixed.add(n("60")); //$NON-NLS-1$
        mixed.add(sampleMap); // a diagram
        mixed.add(sample); // the whole spec
        mixed.add("a string"); //$NON-NLS-1$
        mixed.add(null);

        assertEquals("only the path node should count", "scope=1 in=1 out=1", //$NON-NLS-1$ //$NON-NLS-2$
                shape(new StubExtractionScope(mixed)));
    }

    @Test
    public void theScopeAlwaysReportsTheMapItCameFrom() {
        assertEquals("the scope should know its map", sampleMap, new StubExtractionScope(sel("60")).getMap()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The returned collections are views the caller must not be able to corrupt. */
    @Test(expected = UnsupportedOperationException.class)
    public void theScopeIsNotModifiableByCallers() {
        new StubExtractionScope(sel("60")).getScope().clear(); //$NON-NLS-1$
    }

    @Test(expected = UnsupportedOperationException.class)
    public void theBoundaryIsNotModifiableByCallers() {
        new StubExtractionScope(sel("60")).getInbound().clear(); //$NON-NLS-1$
    }

    // ----------------------------------------------- synthetic: cycles, components

    /** A loop between two selected nodes is genuinely between them, so it is absorbed. */
    @Test
    public void aCycleBetweenSelectedNodesIsAbsorbed() {
        URNspec urn = UrnFactory.eINSTANCE.createURNspec();
        urn.setUrndef(UrncoreFactory.eINSTANCE.createURNdefinition());
        UCMmap map = MapFactory.eINSTANCE.createUCMmap();
        urn.getUrndef().getSpecDiagrams().add(map);

        StartPoint start = MapFactory.eINSTANCE.createStartPoint();
        EmptyPoint a = MapFactory.eINSTANCE.createEmptyPoint();
        EmptyPoint b = MapFactory.eINSTANCE.createEmptyPoint();
        EmptyPoint c = MapFactory.eINSTANCE.createEmptyPoint();
        EndPoint end = MapFactory.eINSTANCE.createEndPoint();
        PathNode[] all = { start, a, b, c, end };
        for (int i = 0; i < all.length; i++)
            map.getNodes().add(all[i]);

        connect(map, start, a);
        connect(map, a, b);
        connect(map, b, c);
        connect(map, c, a); // the loop
        connect(map, c, end);

        StubExtractionScope scope = new StubExtractionScope(Arrays.asList(new PathNode[] { a, c }));

        Set<PathNode> expected = new HashSet<PathNode>(Arrays.asList(new PathNode[] { a, b, c }));
        assertEquals("b lies on a path from a to c and belongs in scope", expected, scope.getScope()); //$NON-NLS-1$
        assertEquals("the loop back into a is internal, not a boundary", //$NON-NLS-1$
                "scope=3 in=1 out=1", shape(scope)); //$NON-NLS-1$
    }

    /** Components holding scoped nodes travel with them. */
    @Test
    public void componentsHoldingScopedNodesAreCollected() {
        URNspec urn = UrnFactory.eINSTANCE.createURNspec();
        urn.setUrndef(UrncoreFactory.eINSTANCE.createURNdefinition());
        UCMmap map = MapFactory.eINSTANCE.createUCMmap();
        urn.getUrndef().getSpecDiagrams().add(map);

        StartPoint start = MapFactory.eINSTANCE.createStartPoint();
        EmptyPoint inside = MapFactory.eINSTANCE.createEmptyPoint();
        EndPoint end = MapFactory.eINSTANCE.createEndPoint();
        map.getNodes().add(start);
        map.getNodes().add(inside);
        map.getNodes().add(end);
        connect(map, start, inside);
        connect(map, inside, end);

        ComponentRef outer = MapFactory.eINSTANCE.createComponentRef();
        ComponentRef innerRef = MapFactory.eINSTANCE.createComponentRef();
        map.getContRefs().add(outer);
        map.getContRefs().add(innerRef);
        innerRef.setParent(outer);
        innerRef.getNodes().add(inside);

        assertEquals("the node should sit in the inner component", innerRef, inside.getContRef()); //$NON-NLS-1$

        StubExtractionScope scope = new StubExtractionScope(Arrays.asList(new PathNode[] { inside }));

        Set<IURNContainerRef> expected = new HashSet<IURNContainerRef>(
                Arrays.asList(new IURNContainerRef[] { innerRef, outer }));
        assertEquals("both the component and its parent should travel with the node", //$NON-NLS-1$
                expected, new HashSet<IURNContainerRef>(scope.getComponents()));
    }

    @Test
    public void nodesOutsideAnyComponentCollectNone() {
        assertTrue("the sample has no components", //$NON-NLS-1$
                new StubExtractionScope(sel("60")).getComponents().isEmpty()); //$NON-NLS-1$
    }

    private void connect(UCMmap map, PathNode from, PathNode to) {
        NodeConnection nc = MapFactory.eINSTANCE.createNodeConnection();
        nc.setSource(from);
        nc.setTarget(to);
        map.getConnections().add(nc);
    }

    // ---------------------------------------------------------- exhaustive pass

    /**
     * Every subset of the sample's eleven nodes -- 2048 of them -- checked against the invariants
     * the command depends on. Cheap, and it reaches shapes nobody writes by hand, which is where
     * this operation has historically gone wrong.
     */
    @Test
    public void invariantsHoldForEverySubsetOfTheSample() {
        List<PathNode> nodes = new ArrayList<PathNode>();
        for (Iterator it = sampleMap.getNodes().iterator(); it.hasNext();)
            nodes.add((PathNode) it.next());

        int checked = 0;
        for (int mask = 0; mask < (1 << nodes.size()); mask++) {
            Set<PathNode> selection = new LinkedHashSet<PathNode>();
            for (int bit = 0; bit < nodes.size(); bit++)
                if ((mask & (1 << bit)) != 0)
                    selection.add(nodes.get(bit));

            StubExtractionScope scope = new StubExtractionScope(selection);
            String where = "selection " + idsOf(selection); //$NON-NLS-1$

            // the scope never loses anything the user picked
            assertTrue(where + ": scope must contain the selection", //$NON-NLS-1$
                    scope.getScope().containsAll(selection));

            // a connection cannot both enter and leave
            Set<NodeConnection> in = new HashSet<NodeConnection>(scope.getInbound());
            Set<NodeConnection> out = new HashSet<NodeConnection>(scope.getOutbound());
            assertTrue(where + ": inbound and outbound must be disjoint", Collections.disjoint(in, out)); //$NON-NLS-1$

            // and every boundary connection must actually straddle the scope
            for (Iterator<NodeConnection> it = in.iterator(); it.hasNext();) {
                NodeConnection nc = it.next();
                assertTrue(where + ": an inbound target must be in scope", scope.getScope().contains(nc.getTarget())); //$NON-NLS-1$
                assertFalse(where + ": an inbound source must be outside", scope.getScope().contains(nc.getSource())); //$NON-NLS-1$
            }
            for (Iterator<NodeConnection> it = out.iterator(); it.hasNext();) {
                NodeConnection nc = it.next();
                assertTrue(where + ": an outbound source must be in scope", scope.getScope().contains(nc.getSource())); //$NON-NLS-1$
                assertFalse(where + ": an outbound target must be outside", scope.getScope().contains(nc.getTarget())); //$NON-NLS-1$
            }

            // closing an already-closed scope is a no-op
            StubExtractionScope again = new StubExtractionScope(new ArrayList<PathNode>(scope.getScope()));
            assertEquals(where + ": closure must be idempotent", scope.getScope(), again.getScope()); //$NON-NLS-1$
            assertEquals(where + ": re-closing must not move the boundary", //$NON-NLS-1$
                    scope.getInbound().size() + ":" + scope.getOutbound().size(), //$NON-NLS-1$
                    again.getInbound().size() + ":" + again.getOutbound().size()); //$NON-NLS-1$

            // a non-empty selection always knows its map
            if (!selection.isEmpty())
                assertEquals(where + ": the map must be identified", sampleMap, scope.getMap()); //$NON-NLS-1$

            checked++;
        }

        assertEquals("every subset should have been checked", 1 << nodes.size(), checked); //$NON-NLS-1$
    }

    /**
     * A stub extracted from a scope must be wireable: the plug-in map needs one start point per
     * entry and one end point per exit, and a scope with neither is not extractable at all. This
     * pins the shape the command will rely on across every connected subset.
     */
    @Test
    public void everyNonEmptyScopeHasAWireableBoundary() {
        List<PathNode> nodes = new ArrayList<PathNode>();
        for (Iterator it = sampleMap.getNodes().iterator(); it.hasNext();)
            nodes.add((PathNode) it.next());

        for (int mask = 1; mask < (1 << nodes.size()); mask++) {
            Set<PathNode> selection = new LinkedHashSet<PathNode>();
            for (int bit = 0; bit < nodes.size(); bit++)
                if ((mask & (1 << bit)) != 0)
                    selection.add(nodes.get(bit));

            StubExtractionScope scope = new StubExtractionScope(selection);
            String where = "selection " + idsOf(selection); //$NON-NLS-1$

            int in = scope.getInbound().size();
            int out = scope.getOutbound().size();

            // The only scope with no boundary at all is one that swallowed the whole map.
            if (in == 0 && out == 0)
                assertEquals(where + ": only a whole-map scope can have no boundary", //$NON-NLS-1$
                        sampleMap.getNodes().size(), scope.getScope().size());

            // Boundary connections are drawn from the map, so they can never outnumber them.
            assertTrue(where + ": boundary cannot exceed the map's connections", //$NON-NLS-1$
                    in + out <= sampleMap.getConnections().size());
        }
    }
}
