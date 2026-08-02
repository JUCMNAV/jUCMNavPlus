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

    /**
     * Start to end absorbs the entire path -- except the start and end points themselves, which
     * belong to the map they punctuate and stay on it. What is left is the map's whole body, with
     * the retained start and end points now sitting on its boundary.
     */
    @Test
    public void startToEndClosesOverTheWholeBodyOfTheMap() {
        StubExtractionScope scope = new StubExtractionScope(sel("14", "16")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("start..end should be everything but the two extremities", //$NON-NLS-1$
                sampleMap.getNodes().size() - 2, scope.getScope().size());
        assertEquals("entered from the start point, left towards the end point", //$NON-NLS-1$
                "scope=9 in=1 out=1", shape(scope)); //$NON-NLS-1$
        assertEquals("the start point should be the one thing entering it", //$NON-NLS-1$
                n("14"), scope.getInbound().get(0).getSource()); //$NON-NLS-1$
        assertEquals("and the end point the one thing it leads to", //$NON-NLS-1$
                n("16"), scope.getOutbound().get(0).getTarget()); //$NON-NLS-1$
    }

    // ------------------------------------------------------- boundary semantics

    @Test
    public void selectingEverythingStillLeavesTheMapItsExtremities() {
        StubExtractionScope scope = new StubExtractionScope(new ArrayList<Object>(sampleMap.getNodes()));
        assertEquals("selecting the whole map extracts its body", //$NON-NLS-1$
                "scope=9 in=1 out=1", shape(scope)); //$NON-NLS-1$
    }

    /**
     * A start point on its own is nothing to extract: it is the map's way in, it stays, and once it
     * has stayed there is no scope left.
     */
    @Test
    public void aStartPointAloneIsNotAnExtraction() {
        assertTrue("a lone start point yields nothing to extract", //$NON-NLS-1$
                new StubExtractionScope(sel("14")).isEmpty()); //$NON-NLS-1$
    }

    /** And the end point the mirror image. */
    @Test
    public void anEndPointAloneIsNotAnExtraction() {
        assertTrue("a lone end point yields nothing to extract", //$NON-NLS-1$
                new StubExtractionScope(sel("16")).isEmpty()); //$NON-NLS-1$
    }

    /**
     * Selecting a start point alongside real content extracts the content and turns the start point
     * into the boundary -- which is what gives the stub its in-path.
     *
     * <p>
     * Moving it instead was the defect: the parent map was left with a stub nothing fed and no
     * start point anywhere, and any scenario anchored on that start point began its traversal
     * inside the plug-in map, with no stub to return through when it reached the far end.
     */
    @Test
    public void aSelectedStartPointBecomesTheBoundaryRatherThanMoving() {
        StubExtractionScope scope = new StubExtractionScope(sel("14", "56")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("the start point must stay on the parent map", scope.getScope().contains(n("14"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("but it must feed the scope, giving the stub its way in", //$NON-NLS-1$
                1, scope.getInbound().size());
        assertEquals("through the connection leaving it", n("14"), scope.getInbound().get(0).getSource()); //$NON-NLS-1$ //$NON-NLS-2$
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

            // the scope never loses anything the user picked, bar the extremities it deliberately
            // leaves on the map they punctuate
            Set<PathNode> expected = new LinkedHashSet<PathNode>(selection);
            expected.remove(n("14")); //$NON-NLS-1$
            expected.remove(n("16")); //$NON-NLS-1$
            assertTrue(where + ": scope must contain the selection", //$NON-NLS-1$
                    scope.getScope().containsAll(expected));
            assertFalse(where + ": the start point must never move", scope.getScope().contains(n("14"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(where + ": the end point must never move", scope.getScope().contains(n("16"))); //$NON-NLS-1$ //$NON-NLS-2$

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

            // a scope with something in it always knows its map. A selection of nothing but the
            // map's extremities leaves an empty scope, and an empty scope has no map to name.
            if (!scope.getScope().isEmpty())
                assertEquals(where + ": the map must be identified", sampleMap, scope.getMap()); //$NON-NLS-1$
            else
                assertTrue(where + ": an empty scope must report empty", scope.isEmpty()); //$NON-NLS-1$

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

            // Since the map's extremities stay behind, anything left to extract is entered and
            // left through something.
            if (!scope.isEmpty())
                assertTrue(where + ": a non-empty scope must have a boundary", in + out > 0); //$NON-NLS-1$

            // Boundary connections are drawn from the map, so they can never outnumber them.
            assertTrue(where + ": boundary cannot exceed the map's connections", //$NON-NLS-1$
                    in + out <= sampleMap.getConnections().size());
        }
    }

    /**
     * The guarantee the command rests on: every non-empty scope yields at least one stub in-path
     * and one out-path.
     *
     * <p>
     * A stub path comes from one of two places -- a connection crossing the boundary, or an
     * extremity the scope owns despite the map's own start and end points having been left behind,
     * which in a well-formed map means an orphan node and in this sample means nothing at all.
     * This asserts across all 2047 non-empty subsets that the two together are never both zero on
     * either side, which is what stops the extraction leaving a parent map whose stub has no way in
     * and whose path has no beginning.
     *
     * <p>
     * The argument, which the sweep only confirms: every node in scope has a predecessor, since the
     * only nodes without one are start points and those never enter the scope. That predecessor is
     * either interior or an inbound crossing; follow the interior ones back and the chain ends at a
     * node fed from outside, because a start point is always outside.
     */
    @Test
    public void everyNonEmptyScopeCanBeGivenAWayInAndAWayOut() {
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

            // A selection of nothing but extremities leaves nothing to extract, and the command
            // declines it rather than building an empty stub.
            if (scope.isEmpty())
                continue;

            assertTrue(where + ": the stub would have no way in", //$NON-NLS-1$
                    scope.getInbound().size() + scope.getOwnStarts().size() >= 1);
            assertTrue(where + ": the stub would have no way out", //$NON-NLS-1$
                    scope.getOutbound().size() + scope.getOwnEnds().size() >= 1);

            // An own extremity is a node of the scope, and it is one precisely when the map gives
            // it nothing to consume or nothing to feed.
            for (Iterator<PathNode> it = scope.getOwnStarts().iterator(); it.hasNext();) {
                PathNode pn = it.next();
                assertTrue(where + ": an own start must be in scope", scope.getScope().contains(pn)); //$NON-NLS-1$
                assertTrue(where + ": an own start must have no predecessor", pn.getPred().isEmpty()); //$NON-NLS-1$
            }
            for (Iterator<PathNode> it = scope.getOwnEnds().iterator(); it.hasNext();) {
                PathNode pn = it.next();
                assertTrue(where + ": an own end must be in scope", scope.getScope().contains(pn)); //$NON-NLS-1$
                assertTrue(where + ": an own end must have no successor", pn.getSucc().isEmpty()); //$NON-NLS-1$
            }
        }
    }

    /**
     * A well-formed map never hands the scope an extremity of its own, because the only nodes that
     * could be one are its start and end points, and those stay put.
     */
    @Test
    public void aWellFormedMapNeverGivesTheScopeAnExtremityOfItsOwn() {
        StubExtractionScope whole = new StubExtractionScope(new ArrayList<Object>(sampleMap.getNodes()));
        assertTrue("the whole map's body owns no extremity", //$NON-NLS-1$
                whole.getOwnStarts().isEmpty() && whole.getOwnEnds().isEmpty());

        StubExtractionScope midPath = new StubExtractionScope(sel("37", "49")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nor does a scope in the middle of a path", //$NON-NLS-1$
                midPath.getOwnStarts().isEmpty() && midPath.getOwnEnds().isEmpty());
    }

    /**
     * The reported case: selecting the start point and RespC. Everything between them is extracted,
     * the start point stays, and the connection out of it is what gives the stub its way in.
     */
    @Test
    public void startPointAndRespCLeaveTheStartPointOnTheParentMap() {
        StubExtractionScope scope = new StubExtractionScope(sel("14", "60")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("start..RespC absorbs everything but the two extremities", //$NON-NLS-1$
                sampleMap.getNodes().size() - 2, scope.getScope().size());
        assertEquals("entered from the start point, left towards the end point", //$NON-NLS-1$
                "scope=9 in=1 out=1", shape(scope)); //$NON-NLS-1$
        assertEquals("the way in comes from the start point that stayed", //$NON-NLS-1$
                n("14"), scope.getInbound().get(0).getSource()); //$NON-NLS-1$
    }

    /** An orphan node -- no predecessor, and not a start point -- is the case that survives. */
    @Test
    public void anOrphanNodeIsAnExtremityTheScopeOwns() {
        URNspec urn = UrnFactory.eINSTANCE.createURNspec();
        urn.setUrndef(UrncoreFactory.eINSTANCE.createURNdefinition());
        UCMmap map = MapFactory.eINSTANCE.createUCMmap();
        urn.getUrndef().getSpecDiagrams().add(map);

        // A generated model can produce this; a hand-drawn one cannot.
        EmptyPoint orphan = MapFactory.eINSTANCE.createEmptyPoint();
        EmptyPoint after = MapFactory.eINSTANCE.createEmptyPoint();
        EndPoint end = MapFactory.eINSTANCE.createEndPoint();
        map.getNodes().add(orphan);
        map.getNodes().add(after);
        map.getNodes().add(end);
        connect(map, orphan, after);
        connect(map, after, end);

        StubExtractionScope scope = new StubExtractionScope(Arrays.asList(new PathNode[] { orphan, after }));

        assertEquals("the orphan is the scope's own way in", //$NON-NLS-1$
                Collections.singletonList((PathNode) orphan), scope.getOwnStarts());
        assertEquals("nothing enters it from the map", 0, scope.getInbound().size()); //$NON-NLS-1$
        assertTrue("so without the orphan the stub would have no in-path at all", //$NON-NLS-1$
                scope.getInbound().size() + scope.getOwnStarts().size() >= 1);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void theOwnStartsAreNotModifiableByCallers() {
        new StubExtractionScope(sel("56")).getOwnStarts().clear(); //$NON-NLS-1$
    }
}
