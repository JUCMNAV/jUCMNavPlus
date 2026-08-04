package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;
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
import seg.jUCMNav.model.util.LabelExtent;
import seg.jUCMNav.model.util.LayoutObjective;
import seg.jUCMNav.model.util.UcmPathDecomposition;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import seg.jUCMNav.views.wizards.AutoLayoutWizard;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;
import urncore.IURNNode;
import urncore.URNmodelElement;

/**
 * The layout objective: does the number actually say what we want it to say?
 *
 * <p>
 * Two kinds of test here, and the split is deliberate. The arithmetic -- turn angles, intersection
 * areas, the four normalisations -- is checked on hand-made rectangles and polylines where the right
 * answer can be worked out on paper. Then the whole thing is checked against the one piece of ground
 * truth available: a map somebody drew by hand. A measure of drawing quality that cannot tell a
 * hand-drawn map from a scattering of nodes over the same area is not measuring drawing quality, and
 * every claim made on the strength of it afterwards would be worthless.
 *
 * <p>
 * All of it is pure -- no editor is opened -- so the class runs in well under a second and new cases
 * are cheap to add.
 *
 * @author Claude
 */
public class AutoLayoutObjectiveTest {

    private URNspec sample;
    private UCMmap sampleMap;

    @Before
    public void loadSample() throws Exception {
        ucm.map.impl.MapPackageImpl.init();
        ResourceSet rs = new ResourceSetImpl();
        rs.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jucm", new XMIResourceFactoryImpl()); //$NON-NLS-1$
        Resource r = rs.createResource(URI.createURI("sample.jucm")); //$NON-NLS-1$
        r.load(AutoLayoutObjectiveTest.class.getResourceAsStream("/seg/jUCMNav/tests/commands/IssueTrackerSyntheticLog_variant.jucm"), //$NON-NLS-1$
                new HashMap<Object, Object>());

        sample = (URNspec) r.getContents().get(0);
        for (Iterator it = sample.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                sampleMap = (UCMmap) d;
        }
        assertNotNull("the sample should hold a UCM map", sampleMap); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------------ turn angles

    @Test
    public void aStraightRunTurnsNowhere() {
        double[] angles = LayoutObjective.turnAngles(polyline(new int[] { 0, 0, 100, 0, 200, 0, 300, 0 }));

        assertEquals("two interior vertices", 2, angles.length); //$NON-NLS-1$
        for (int i = 0; i < angles.length; i++)
            assertEquals("a straight line turns nowhere", 0.0, angles[i], 1e-9); //$NON-NLS-1$
    }

    @Test
    public void aRightAngleTurnsNinetyDegrees() {
        double[] angles = LayoutObjective.turnAngles(polyline(new int[] { 0, 0, 100, 0, 100, 100 }));

        assertEquals("one interior vertex", 1, angles.length); //$NON-NLS-1$
        assertEquals("a right angle", 90.0, Math.toDegrees(angles[0]), 1e-6); //$NON-NLS-1$
    }

    @Test
    public void doublingBackIsHalfATurn() {
        double[] angles = LayoutObjective.turnAngles(polyline(new int[] { 0, 0, 100, 0, 0, 0 }));

        assertEquals("a reversal is pi", 180.0, Math.toDegrees(angles[0]), 1e-6); //$NON-NLS-1$
    }

    @Test
    public void tooShortToTurn() {
        assertEquals(0, LayoutObjective.turnAngles(polyline(new int[] { 0, 0, 10, 10 })).length);
        assertEquals(0, LayoutObjective.turnAngles(null).length);
    }

    /**
     * Coincident points state no direction. Claiming a turn across them would put a spurious
     * right angle into the score for every degenerate route -- and degenerate routes happen.
     */
    @Test
    public void coincidentPointsClaimNoTurn() {
        double[] angles = LayoutObjective.turnAngles(polyline(new int[] { 50, 50, 50, 50, 200, 0 }));

        assertEquals(1, angles.length);
        assertEquals("no direction, so no turn", 0.0, angles[0], 1e-9); //$NON-NLS-1$
    }

    /**
     * The two ways of measuring a turn must not drift apart: {@code ChainPlacement.sharpestTurn}
     * bounds the worst corner in the pipeline test, and this class squares every corner. If they
     * ever disagreed, one of the two suites would be enforcing something nobody believes.
     */
    @Test
    public void theWorstTurnAngleIsTheSharpestTurn() {
        PointList route = polyline(new int[] { 0, 0, 100, 0, 140, 90, 60, 140, 60, 300, 200, 380 });

        double worst = 0;
        double[] angles = LayoutObjective.turnAngles(route);
        for (int i = 0; i < angles.length; i++)
            worst = Math.max(worst, Math.toDegrees(angles[i]));

        assertEquals("both must measure the same corners", ChainPlacement.sharpestTurn(route), worst, 1e-9); //$NON-NLS-1$
    }

    /**
     * Why the objective sums squares instead of taking the maximum, stated as a test: one savage
     * corner and a run of gentle ones are different drawings, and the maximum cannot tell them
     * apart. Both routes below turn 90 degrees at their worst.
     */
    @Test
    public void manyMediocreCornersScoreWorseThanOneBadOne() {
        // Both have six points and so four corners; both turn 90 degrees at their worst.
        PointList oneBadCorner = polyline(new int[] { 0, 0, 300, 0, 600, 0, 900, 0, 900, 300, 900, 600 });
        PointList allBadCorners = polyline(new int[] { 0, 0, 300, 0, 300, 300, 600, 300, 600, 600, 900, 600 });

        assertEquals("both turn 90 degrees at worst", ChainPlacement.sharpestTurn(oneBadCorner), //$NON-NLS-1$
                ChainPlacement.sharpestTurn(allBadCorners), 1e-9);

        assertTrue("but four right angles must score worse than one", //$NON-NLS-1$
                bendingOf(allBadCorners) > bendingOf(oneBadCorner));
    }

    // ------------------------------------------------------------------------ intersection area

    @Test
    public void rectanglesThatMissShareNothing() {
        assertEquals(0.0, LayoutObjective.intersectionArea(new Rectangle(0, 0, 10, 10), new Rectangle(50, 50, 10, 10)), 1e-9);
    }

    /** Touching along an edge is not overlapping: the shared region has no area. */
    @Test
    public void rectanglesThatTouchShareNothing() {
        assertEquals(0.0, LayoutObjective.intersectionArea(new Rectangle(0, 0, 10, 10), new Rectangle(10, 0, 10, 10)), 1e-9);
    }

    @Test
    public void rectanglesThatOverlapShareTheirIntersection() {
        assertEquals(6.0 * 4.0, LayoutObjective.intersectionArea(new Rectangle(0, 0, 10, 10), new Rectangle(4, 6, 20, 20)), 1e-9);
    }

    @Test
    public void aContainedRectangleSharesAllOfItself() {
        assertEquals(4.0 * 4.0, LayoutObjective.intersectionArea(new Rectangle(0, 0, 100, 100), new Rectangle(10, 10, 4, 4)), 1e-9);
    }

    // ------------------------------------------------------------------------ the four terms

    /** A box with no slack around its contents is not sprawling, whatever its absolute size. */
    @Test
    public void aTightComponentDoesNotSprawl() {
        LayoutObjective.Score score = LayoutObjective.combine(null, boxes(new Rectangle(0, 0, 100, 100)), areas(100.0 * 100.0), null);

        assertEquals("box area equals content area", 1.0, score.sprawl, 1e-9); //$NON-NLS-1$
    }

    /**
     * Sprawl is the reciprocal of how much of the box is filled -- a box holding a seventh of its
     * own area in nodes reads as 6.7.
     *
     * <p>
     * Resist reading that against the "roughly 15% ink" recorded for IMS-2022 map 1 on issue #30:
     * that figure is ink over the whole drawing, and this term is content over one component's box.
     * They are different fractions and a coincidence of wording. The hand-drawn baseline measured
     * by {@link #theHandDrawnMapBeatsTheSameNodesScattered} is 15.05, so 6.7 here would be a
     * <i>tighter</i> component than a person draws, not a pathological one.
     */
    @Test
    public void aNearlyEmptyComponentSprawls() {
        LayoutObjective.Score score = LayoutObjective.combine(null, boxes(new Rectangle(0, 0, 1000, 1000)), areas(0.15 * 1000 * 1000), null);

        assertEquals("a box one seventh full sprawls by about 6.7", 1 / 0.15, score.sprawl, 0.01); //$NON-NLS-1$
    }

    /** No components asked about means no verdict, not an infinitely bad one. */
    @Test
    public void noComponentsIsNeutralNotInfinite() {
        LayoutObjective.Score score = LayoutObjective.combine(null, new ArrayList<Rectangle>(), new ArrayList<Double>(), null);

        assertEquals(1.0, score.sprawl, 1e-9);
        assertEquals(0.0, score.overlap, 1e-9);
        assertEquals(0.0, score.bending, 1e-9);
        assertEquals(0.0, score.spread, 1e-9);
        assertTrue("a total must always be a number", !Double.isNaN(score.total())); //$NON-NLS-1$
    }

    @Test
    public void overlapIsMeasuredAgainstTotalBoxArea() {
        // Two 10x10 boxes sharing a 5x10 strip: 50 of overlap against 200 of box.
        LayoutObjective.Score score = LayoutObjective.combine(null, null, null, boxes(new Rectangle(0, 0, 10, 10), new Rectangle(5, 0, 10, 10)));

        assertEquals(50.0, score.totalOverlapArea, 1e-9);
        assertEquals(50.0 / 200.0, score.overlap, 1e-9);
    }

    @Test
    public void nodesAtTheNaturalSpacingSpreadByOne() {
        int gap = (int) LayoutObjective.NATURAL_SPACING;
        List<PointList> routes = new ArrayList<PointList>();
        routes.add(polyline(new int[] { 0, 0, gap, 0, 2 * gap, 0, 3 * gap, 0 }));

        LayoutObjective.Score score = LayoutObjective.combine(routes, null, null, null);

        assertEquals("three segments", 3, score.segments); //$NON-NLS-1$
        assertEquals("spread is measured in natural gaps", 1.0, score.spread, 1e-9); //$NON-NLS-1$
        assertEquals("and a straight run does not bend", 0.0, score.bending, 1e-9); //$NON-NLS-1$
    }

    @Test
    public void nothingPlacedScoresNothing() {
        LayoutObjective.Score score = LayoutObjective.evaluate(null, new LinkedHashMap<IURNNode, Point>(), null);

        assertEquals(0, score.boxes);
        assertTrue("a total must always be a number", !Double.isNaN(score.total())); //$NON-NLS-1$
    }

    // -------------------------------------------------------------------------------- stitching

    /**
     * The corners at pass-through junctions must be counted.
     *
     * <p>
     * A node bound to a component is a junction however ordinary it looks -- {@code
     * UcmPathDecomposition.isJunction} says so, and it is right to, since the component decides
     * where it goes. The consequence for scoring is that a map with components is chopped into
     * many short chains, and measuring each separately never looks at the joins between them. On
     * this sample that is the majority of the corners in the drawing.
     */
    @Test
    public void turnsAtPassThroughJunctionsAreMeasuredToo() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Point> positions = storedPositions();

        int perChainVertices = 0;
        for (Iterator<UcmPathDecomposition.Chain> it = d.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            perChainVertices += Math.max(0, chain.length() + 2 - 2);
        }

        int stitchedVertices = 0, stitchedSegments = 0;
        List<PointList> routes = LayoutObjective.routesOf(d, positions);
        for (int i = 0; i < routes.size(); i++) {
            stitchedVertices += LayoutObjective.turnAngles(routes.get(i)).length;
            stitchedSegments += routes.get(i).size() - 1;
        }

        assertTrue("stitching must expose corners that per-chain measurement misses: " //$NON-NLS-1$
                + stitchedVertices + " against " + perChainVertices, stitchedVertices > perChainVertices); //$NON-NLS-1$

        // Stitching joins polylines at a shared point, so it must not invent or lose any segment --
        // otherwise the spread term would quietly change meaning along with the bending term.
        int chainSegments = 0;
        for (Iterator<UcmPathDecomposition.Chain> it = d.getChains().iterator(); it.hasNext();)
            chainSegments += it.next().length() + 1;

        assertEquals("stitching must not change how much path there is", chainSegments, stitchedSegments); //$NON-NLS-1$
    }

    /** A fork is left as a break: which branch continues the incoming path is not a fact of the model. */
    @Test
    public void routesBreakAtGenuineForks() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        List<PointList> routes = LayoutObjective.routesOf(d, storedPositions());

        assertTrue("a branching map cannot be one single route", routes.size() > 1); //$NON-NLS-1$
    }

    // ----------------------------------------------------------------------------- ground truth

    /**
     * The test the whole class is for.
     *
     * <p>
     * The sample map's stored coordinates are where a person put those nodes. Scattering the same
     * nodes at random over the same rectangle is, by construction, a worse drawing of the same
     * model -- it is the null hypothesis for "this map is laid out at all". An objective that does
     * not prefer the hand-drawn arrangement is measuring nothing, and any layout tuned to it would
     * be tuned to noise.
     *
     * <p>
     * Every term is asserted separately as well as the total, so a regression names which of the
     * four stopped working rather than just that the sum moved.
     */
    @Test
    public void theHandDrawnMapBeatsTheSameNodesScattered() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);

        Map<IURNNode, Point> drawn = storedPositions();
        Map<IURNNode, Dimension> sizes = extents(drawn);

        LayoutObjective.Score hand = LayoutObjective.evaluate(d, drawn, sizes);
        LayoutObjective.Score scattered = LayoutObjective.evaluate(d, scatter(drawn), sizes);

        assertTrue("the sample should have components to measure", hand.components > 0); //$NON-NLS-1$
        assertTrue("and chains to measure", hand.segments > 0); //$NON-NLS-1$

        // The baseline every future attempt is measured against, printed rather than only asserted:
        // a layout that scores worse than the hand-drawn map is not an improvement whatever it
        // looks like, and these two lines are what makes that a fact rather than an impression.
        System.out.println("objective, hand-drawn: " + hand); //$NON-NLS-1$
        System.out.println("objective, scattered : " + scattered); //$NON-NLS-1$

        assertTrue("hand-drawn paths must bend less: " + hand.bending + " against " + scattered.bending, //$NON-NLS-1$ //$NON-NLS-2$
                hand.bending < scattered.bending);
        assertTrue("hand-drawn components must sprawl less: " + hand.sprawl + " against " + scattered.sprawl, //$NON-NLS-1$ //$NON-NLS-2$
                hand.sprawl < scattered.sprawl);
        assertTrue("hand-drawn labels must overlap less: " + hand.overlap + " against " + scattered.overlap, //$NON-NLS-1$ //$NON-NLS-2$
                hand.overlap < scattered.overlap);
        assertTrue("hand-drawn nodes must sit closer: " + hand.spread + " against " + scattered.spread, //$NON-NLS-1$ //$NON-NLS-2$
                hand.spread < scattered.spread);

        assertTrue("and so the total must be lower: " + hand + " against " + scattered, //$NON-NLS-1$ //$NON-NLS-2$
                hand.total() < scattered.total());
    }

    /**
     * Sliding the whole drawing must not change what it scores. Layouts are compared across runs
     * that centre the result differently, so a score that moved with the origin would make every
     * such comparison meaningless.
     */
    @Test
    public void theScoreDoesNotDependOnWhereTheDrawingSits() {
        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Point> drawn = storedPositions();
        Map<IURNNode, Dimension> sizes = extents(drawn);

        Map<IURNNode, Point> moved = new LinkedHashMap<IURNNode, Point>();
        for (Iterator<IURNNode> it = drawn.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Point at = drawn.get(node);
            moved.put(node, new Point(at.x + 1500, at.y - 400));
        }

        LayoutObjective.Score here = LayoutObjective.evaluate(d, drawn, sizes);
        LayoutObjective.Score there = LayoutObjective.evaluate(d, moved, sizes);

        assertEquals(here.bending, there.bending, 1e-9);
        assertEquals(here.sprawl, there.sprawl, 1e-9);
        assertEquals(here.overlap, there.overlap, 1e-9);
        assertEquals(here.spread, there.spread, 1e-9);
    }

    /**
     * A component's box is measured around everything drawn inside it, however deeply nested --
     * so a node bound to a child counts towards the parent's box too. Getting this wrong would
     * flatter every model with nested components, which is most of the real ones.
     */
    @Test
    public void aComponentIsMeasuredAroundEverythingInsideIt() {
        Map<IURNNode, Point> positions = storedPositions();
        Map<IURNNode, Dimension> sizes = extents(positions);

        List<Rectangle> boxes = new ArrayList<Rectangle>();
        List<Double> contents = new ArrayList<Double>();
        LayoutObjective.componentBoxesOf(positions, sizes, LayoutObjective.DEFAULT_COMPONENT_MARGIN, boxes, contents);

        assertTrue("the sample should have components", boxes.size() > 0); //$NON-NLS-1$
        for (int i = 0; i < boxes.size(); i++) {
            assertTrue("a component box must hold its contents: " + boxes.get(i), //$NON-NLS-1$
                    (double) boxes.get(i).width * boxes.get(i).height >= contents.get(i).doubleValue());
        }

        // Every node bound to a container must be inside that container's box, which is the hard
        // constraint the solver will have to respect and the thing sprawl is traded against.
        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            if (node.getContRef() == null)
                continue;

            Rectangle nodeBox = LayoutObjective.boxOf(node, positions, sizes);
            boolean held = false;
            for (int i = 0; i < boxes.size() && !held; i++)
                held = boxes.get(i).contains(nodeBox);

            assertTrue("a bound node must lie inside some component box: " + nodeBox, held); //$NON-NLS-1$
        }
    }

    /**
     * What the pipeline in the tree right now scores, against the map a person drew.
     *
     * <p>
     * The reason this class was written first. Every judgement about auto-layout so far has come
     * from looking at a PNG, and the four approaches on issue #30 were argued about for days each;
     * this puts the current implementation and the hand-drawn original on the same axis, so the
     * redesign starts from a measured position rather than an impression of one.
     *
     * <p>
     * Deliberately asserts almost nothing. The bar it does enforce -- beat a random scattering of
     * the same nodes -- is one any layout worth running must clear, and it is not currently known
     * whether the pipeline beats the hand-drawn map or by how much. Printing the comparison is the
     * point; tightening it into an assertion is something to do once there is a target to hold.
     */
    @Test
    public void theCurrentPipelineScoredAgainstTheHandDrawnMap() throws Exception {
        AutoLayoutPreferences.createPreferences();
        String dot = AutoLayoutPreferences.locateDot();
        org.junit.Assume.assumeTrue("Graphviz not installed; the pipeline cannot be scored", dot != null); //$NON-NLS-1$

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        String source = ExportContractedDOT.convert(sampleMap, d);

        // stderr to nowhere rather than to a pipe nobody drains: a full 64KB pipe blocks dot
        // forever, which is how a 1200-node model used to hang the workbench with no error at all.
        Process p = new ProcessBuilder(dot, "-Tplain").redirectError(ProcessBuilder.Redirect.DISCARD).start(); //$NON-NLS-1$
        p.getOutputStream().write(source.getBytes());
        p.getOutputStream().close();

        StringBuffer out = new StringBuffer();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null)
            out.append(line).append("\n"); //$NON-NLS-1$
        reader.close();

        Map<IURNNode, Point> laidOut = AutoLayoutWizard.placeUcm(d, new PlainLayout(out.toString()));

        // One set of sizes for all three, so the comparison is about arrangement and nothing else.
        Map<IURNNode, Dimension> sizes = extents(storedPositions());

        LayoutObjective.Score pipeline = LayoutObjective.evaluate(d, laidOut, sizes);
        LayoutObjective.Score hand = LayoutObjective.evaluate(d, storedPositions(), sizes);
        LayoutObjective.Score scattered = LayoutObjective.evaluate(d, scatter(storedPositions()), sizes);

        System.out.println("objective, pipeline  : " + pipeline); //$NON-NLS-1$
        System.out.println("objective, hand-drawn: " + hand); //$NON-NLS-1$
        System.out.println("objective, scattered : " + scattered); //$NON-NLS-1$

        assertTrue("auto-layout must at least beat scattering the nodes at random: " //$NON-NLS-1$
                + pipeline + " against " + scattered, pipeline.total() < scattered.total()); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------------------- helpers

    /** The sample's own coordinates -- a person's idea of where these nodes go. */
    private Map<IURNNode, Point> storedPositions() {
        Map<IURNNode, Point> positions = new LinkedHashMap<IURNNode, Point>();
        for (Iterator it = sampleMap.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            positions.put(pn, new Point(pn.getX(), pn.getY()));
        }
        return positions;
    }

    private Map<IURNNode, Dimension> extents(Map<IURNNode, Point> positions) {
        Map<IURNNode, Dimension> sizes = new LinkedHashMap<IURNNode, Dimension>();
        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            sizes.put(node, LabelExtent.including((URNmodelElement) node, new Dimension(40, 40)));
        }
        return sizes;
    }

    /**
     * The same nodes, at random, over the same rectangle the hand-drawn map occupies.
     *
     * <p>
     * Fixed seed and an arithmetic generator rather than {@code Math.random}, so a failure is the
     * same failure tomorrow. Same area, same nodes, same topology -- only the arrangement differs,
     * which is exactly the variable the objective claims to measure.
     */
    private Map<IURNNode, Point> scatter(Map<IURNNode, Point> positions) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (Iterator<Point> it = positions.values().iterator(); it.hasNext();) {
            Point at = it.next();
            left = Math.min(left, at.x);
            top = Math.min(top, at.y);
            right = Math.max(right, at.x);
            bottom = Math.max(bottom, at.y);
        }

        Map<IURNNode, Point> scattered = new LinkedHashMap<IURNNode, Point>();
        long seed = 20260804L;
        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            int x = left + (int) Math.abs((seed >>> 17) % Math.max(1, right - left + 1));
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            int y = top + (int) Math.abs((seed >>> 17) % Math.max(1, bottom - top + 1));
            scattered.put(node, new Point(x, y));
        }
        return scattered;
    }

    private double bendingOf(PointList route) {
        List<PointList> routes = new ArrayList<PointList>();
        routes.add(route);
        return LayoutObjective.combine(routes, null, null, null).bending;
    }

    private static PointList polyline(int[] xy) {
        PointList pts = new PointList();
        for (int i = 0; i + 1 < xy.length; i += 2)
            pts.addPoint(new Point(xy[i], xy[i + 1]));
        return pts;
    }

    private static List<Rectangle> boxes(Rectangle a) {
        List<Rectangle> list = new ArrayList<Rectangle>();
        list.add(a);
        return list;
    }

    private static List<Rectangle> boxes(Rectangle a, Rectangle b) {
        List<Rectangle> list = boxes(a);
        list.add(b);
        return list;
    }

    private static List<Double> areas(double a) {
        List<Double> list = new ArrayList<Double>();
        list.add(Double.valueOf(a));
        return list;
    }
}
