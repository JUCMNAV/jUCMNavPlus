package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Iterator;

import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.importexport.ExportContractedDOT;
import seg.jUCMNav.importexport.PlainLayout;
import seg.jUCMNav.model.util.ChainPlacement;
import seg.jUCMNav.model.util.UcmPathDecomposition;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import seg.jUCMNav.views.wizards.AutoLayoutWizard;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;
import urncore.URNmodelElement;

/**
 * The auto-layout pipeline, tested where it can be tested: the parts that turn text and topology
 * into coordinates, with no workbench and no Graphviz installation.
 *
 * <p>
 * That split is the point. The old implementation could only be exercised by opening an editor,
 * having the right Graphviz on the path, and looking at the result -- which is why it went years
 * emitting nothing at all on any current release without anyone noticing. Everything asserted here
 * is a pure function.
 *
 * @author Claude
 */
public class AutoLayoutPipelineTest {

    /** Real Graphviz 9.0.0 output, kept verbatim. Regenerate with {@code dot -Tplain}. */
    private static final String PLAIN = "graph 0.33498 25.375 4.9022\n" //$NON-NLS-1$
            + "node CheapTrick0 13.5 2.0622 23.3 1.68 CheapTrick0 solid ellipse black lightgrey\n" //$NON-NLS-1$
            + "node UrnNode5 0.91667 2.0622 1.3769 0.5 UrnNode5 solid ellipse black lightgrey\n" //$NON-NLS-1$
            + "node UrnNode8 0.91667 0.47222 1.3769 0.5 UrnNode8 solid ellipse black lightgrey\n" //$NON-NLS-1$
            + "edge UrnNode5 UrnNode8 4 0.91667 1.8111 0.91667 1.5656 0.91667 1.1779 0.91667 0.8852 solid black\n" //$NON-NLS-1$
            + "stop\n"; //$NON-NLS-1$

    private URNspec sample;
    private UCMmap sampleMap;

    @Before
    public void loadSample() throws Exception {
        ucm.map.impl.MapPackageImpl.init();
        ResourceSet rs = new ResourceSetImpl();
        rs.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jucm", new XMIResourceFactoryImpl()); //$NON-NLS-1$
        Resource r = rs.createResource(URI.createURI("sample.jucm")); //$NON-NLS-1$
        r.load(AutoLayoutPipelineTest.class.getResourceAsStream("/seg/jUCMNav/tests/commands/IssueTrackerSyntheticLog_variant.jucm"), //$NON-NLS-1$
                new HashMap<Object, Object>());

        sample = (URNspec) r.getContents().get(0);
        for (Iterator it = sample.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                sampleMap = (UCMmap) d;
        }
        assertNotNull("the sample should hold a UCM map", sampleMap); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------- plain parsing

    @Test
    public void parsesGraphvizPlainOutput() throws Exception {
        PlainLayout layout = new PlainLayout(PLAIN);

        assertEquals("three nodes", 3, layout.nodeCount()); //$NON-NLS-1$
        assertEquals("height in points", 4.9022 * 72, layout.getHeight(), 0.01); //$NON-NLS-1$

        PlainLayout.Node n = layout.getNode("UrnNode5"); //$NON-NLS-1$
        assertNotNull("UrnNode5 should be placed", n); //$NON-NLS-1$
        assertEquals("x in points", 0.91667 * 72, n.x, 0.01); //$NON-NLS-1$
        assertEquals("y in points", 2.0622 * 72, n.y, 0.01); //$NON-NLS-1$

        PlainLayout.Edge e = layout.getEdge("UrnNode5", "UrnNode8"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the edge should be there", e); //$NON-NLS-1$
        assertEquals("with four control points", 4, e.size()); //$NON-NLS-1$
    }

    /**
     * The decimal bounding boxes and changed indentation of modern Graphviz are exactly what the
     * old {@code -Tdot} regexes could not read. The plain format has none of that shape to trip on.
     */
    @Test
    public void toleratesRecordsItCannotUnderstand() throws Exception {
        PlainLayout layout = new PlainLayout("graph 1 10 20\n" //$NON-NLS-1$
                + "node UrnNode1 1 2 3 4 lbl solid ellipse black lightgrey\n" //$NON-NLS-1$
                + "node UrnNodeBroken not-a-number 2 3 4 lbl\n" //$NON-NLS-1$
                + "something entirely unexpected\n" //$NON-NLS-1$
                + "stop\n"); //$NON-NLS-1$

        assertEquals("the good node survives the bad one", 1, layout.nodeCount()); //$NON-NLS-1$
        assertNotNull(layout.getNode("UrnNode1")); //$NON-NLS-1$
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsOutputThatIsNotPlain() throws Exception {
        // What -Tdot returns. Failing loudly beats silently positioning nothing, which is what
        // the previous implementation did for years.
        new PlainLayout("digraph G {\n\tgraph [bb=\"0,0,1827,352.96\"];\n}\n"); //$NON-NLS-1$
    }

    // --------------------------------------------------------------------- chain decomposition

    @Test
    public void contractsTheSampleToAHandfulOfJunctions() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);

        assertTrue("every junction is a node of the map", sampleMap.getNodes().containsAll(d.getJunctions())); //$NON-NLS-1$
        assertTrue("contraction should shrink the problem: " + d.describe(), //$NON-NLS-1$
                d.getJunctions().size() < sampleMap.getNodes().size());

        // Every node is either a junction or the interior of exactly one chain -- nothing is
        // placed twice, and nothing is left without a position.
        int interior = 0;
        for (Iterator<UcmPathDecomposition.Chain> it = d.getChains().iterator(); it.hasNext();)
            interior += it.next().length();

        assertEquals("junctions plus chain interiors must cover the map exactly", //$NON-NLS-1$
                sampleMap.getNodes().size(), d.getJunctions().size() + interior);
    }

    @Test
    public void forksJoinsAndPathEndsAreJunctions() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);

        String[] mustBeJunctions = { "2", "3", "6", "7", "11", "12", "29" }; // start, end, AND-fork/join, loop //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        for (int i = 0; i < mustBeJunctions.length; i++)
            assertTrue("node " + mustBeJunctions[i] + " must be a junction", //$NON-NLS-1$ //$NON-NLS-2$
                    d.getJunctions().contains(node(mustBeJunctions[i])));
    }

    /**
     * A pass-through inside a component is a junction anyway. Distributing it along a chain would
     * move it out of the component that performs it, which is most of what a UCM says.
     */
    @Test
    public void aNodeInsideAComponentIsPlacedInItsOwnRight() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);

        PathNode createTicket = node("4"); // Reporter //$NON-NLS-1$
        assertNotNull("the sample should hold Create Ticket", createTicket); //$NON-NLS-1$
        assertNotNull("and it should sit in a component", createTicket.getContRef()); //$NON-NLS-1$
        assertEquals("one predecessor", 1, createTicket.getPred().size()); //$NON-NLS-1$
        assertEquals("one successor", 1, createTicket.getSucc().size()); //$NON-NLS-1$

        assertTrue("a pass-through in a component must still be a junction", //$NON-NLS-1$
                d.getJunctions().contains(createTicket));
    }

    // ------------------------------------------------------------------------ chain placement

    @Test
    public void spreadsNodesEvenlyAlongAStraightRoute() {
        PointList route = new PointList();
        route.addPoint(new Point(0, 0));
        route.addPoint(new Point(400, 0));

        PointList placed = ChainPlacement.distribute(route, 3);

        assertEquals("three nodes asked for, three placed", 3, placed.size()); //$NON-NLS-1$
        assertEquals("evenly spaced", 100, placed.getPoint(0).x, 2); //$NON-NLS-1$
        assertEquals("evenly spaced", 200, placed.getPoint(1).x, 2); //$NON-NLS-1$
        assertEquals("evenly spaced", 300, placed.getPoint(2).x, 2); //$NON-NLS-1$
    }

    /**
     * The property the whole design rests on. jUCMNav interpolates a cubic spline through these
     * points, and such a spline overshoots when spacing is uneven or a turn is sharp -- so the
     * placement has to guarantee neither.
     */
    @Test
    public void producesEvenSpacingAndGentleTurnsOnACorner() {
        PointList route = new PointList();
        route.addPoint(new Point(0, 0));
        route.addPoint(new Point(300, 0));
        route.addPoint(new Point(300, 300)); // a right angle, the worst case for overshoot

        assertEquals("the raw route turns 90 degrees", 90, ChainPlacement.sharpestTurn(route), 1); //$NON-NLS-1$

        PointList placed = ChainPlacement.distribute(route, 8);

        assertEquals("eight nodes", 8, placed.size()); //$NON-NLS-1$
        assertTrue("spacing must be near-uniform, was " + ChainPlacement.spacingRatio(placed), //$NON-NLS-1$
                ChainPlacement.spacingRatio(placed) < 1.5);
        assertTrue("no sharp turn should survive, worst was " + ChainPlacement.sharpestTurn(placed), //$NON-NLS-1$
                ChainPlacement.sharpestTurn(placed) < 35);
    }

    @Test
    public void placesNothingWhenNothingIsAsked() {
        PointList route = new PointList();
        route.addPoint(new Point(0, 0));
        route.addPoint(new Point(100, 0));

        assertEquals(0, ChainPlacement.distribute(route, 0).size());
        assertEquals(0, ChainPlacement.distribute(null, 5).size());
    }

    /** A route of zero length must not stack every node on one pixel. */
    @Test
    public void separatesNodesOnADegenerateRoute() {
        PointList route = new PointList();
        route.addPoint(new Point(50, 50));
        route.addPoint(new Point(50, 50));

        PointList placed = ChainPlacement.distribute(route, 3);

        assertEquals(3, placed.size());
        assertTrue("the nodes must not coincide", //$NON-NLS-1$
                placed.getPoint(0).x != placed.getPoint(1).x || placed.getPoint(1).x != placed.getPoint(2).x);
    }

    @Test
    public void smoothingKeepsTheRouteEndsWhereTheJunctionsAre() {
        PointList route = new PointList();
        route.addPoint(new Point(10, 20));
        route.addPoint(new Point(300, 20));
        route.addPoint(new Point(300, 400));

        PointList smooth = ChainPlacement.smooth(route);

        assertEquals("the first point is a junction and must not move", new Point(10, 20), smooth.getPoint(0)); //$NON-NLS-1$
        assertEquals("nor the last", new Point(300, 400), smooth.getPoint(smooth.size() - 1)); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------- end to end

    /**
     * The whole pipeline against a real Graphviz on a real model: decompose, emit the contracted
     * DOT, run dot, parse, place. Skipped where Graphviz is absent, which is the honest thing --
     * this is the one part that cannot be tested without it.
     */
    @Test
    public void laysOutTheSampleEndToEnd() throws Exception {
        AutoLayoutPreferences.createPreferences();
        String dot = AutoLayoutPreferences.locateDot();
        org.junit.Assume.assumeTrue("Graphviz not installed; end-to-end layout not exercised", dot != null); //$NON-NLS-1$

        UcmPathDecomposition decomposition = new UcmPathDecomposition(sampleMap);
        String source = ExportContractedDOT.convert(sampleMap, decomposition);

        Process p = new ProcessBuilder(dot, "-Tplain").redirectErrorStream(false).start(); //$NON-NLS-1$
        p.getOutputStream().write(source.getBytes());
        p.getOutputStream().close();

        StringBuffer out = new StringBuffer();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null)
            out.append(line).append("\n"); //$NON-NLS-1$
        reader.close();

        PlainLayout layout = new PlainLayout(out.toString());
        java.util.Map<urncore.IURNNode, Point> positions = AutoLayoutWizard.placeUcm(decomposition, layout);

        // Every node gets a position. Silently leaving nodes where they were is precisely the
        // failure this work exists to end.
        assertEquals("every node of the map must be positioned", //$NON-NLS-1$
                sampleMap.getNodes().size(), positions.size());

        // And no two land on the same pixel, which would mean a chain collapsed.
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Iterator<Point> it = positions.values().iterator(); it.hasNext();) {
            Point at = it.next();
            assertTrue("two nodes placed at " + at, seen.add(at.x + "," + at.y)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private PathNode node(String id) {
        for (Iterator it = sampleMap.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (id.equals(((URNmodelElement) pn).getId()))
                return pn;
        }
        return null;
    }
}
