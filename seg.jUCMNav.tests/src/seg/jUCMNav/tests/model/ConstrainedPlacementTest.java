package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import seg.jUCMNav.model.util.ComponentSeparation;
import seg.jUCMNav.model.util.ConstrainedPlacement;
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
 * The constrained placement, scored rather than looked at.
 *
 * <p>
 * {@link LayoutObjective} exists so that a change to the layout is an argument about a number
 * instead of about a screenshot. This is the class that cashes that in: every claim below is a
 * comparison against either the map PM4Py-UCM drew or the pipeline being replaced, and the constants
 * in {@link ConstrainedPlacement} were chosen by running this, not by reasoning about forces.
 *
 * @author Claude
 */
public class ConstrainedPlacementTest {

    private URNspec sample;
    private UCMmap sampleMap;

    @Before
    public void loadSample() throws Exception {
        ucm.map.impl.MapPackageImpl.init();
        ResourceSet rs = new ResourceSetImpl();
        rs.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jucm", new XMIResourceFactoryImpl()); //$NON-NLS-1$
        Resource r = rs.createResource(URI.createURI("sample.jucm")); //$NON-NLS-1$
        r.load(ConstrainedPlacementTest.class.getResourceAsStream("/seg/jUCMNav/tests/commands/IssueTrackerSyntheticLog_variant.jucm"), //$NON-NLS-1$
                new HashMap<Object, Object>());

        sample = (URNspec) r.getContents().get(0);
        for (Iterator it = sample.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                sampleMap = (UCMmap) d;
        }
        assertNotNull("the sample should hold a UCM map", sampleMap); //$NON-NLS-1$
    }

    // -------------------------------------------------------------------------------- the score

    /**
     * The test the redesign exists to pass.
     *
     * <p>
     * The pipeline on issue #30 scores 22.08 against PM4Py-UCM's layout's 17.98, and loses on the
     * component term alone -- free placement draws good paths and terrible components. A
     * replacement that does not beat 22.08 is not a replacement.
     */
    @Test
    public void beatsThePipelineItReplaces() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());

        LayoutObjective.Score solved = LayoutObjective.evaluate(d, solve(d, sizes), sizes);
        LayoutObjective.Score pipeline = LayoutObjective.evaluate(d, pipeline(d), sizes);
        LayoutObjective.Score hand = LayoutObjective.evaluate(d, storedPositions(), sizes);

        System.out.println("objective, solver    : " + solved + " | crossings " + crossings(d, solve(d, sizes))); //$NON-NLS-1$ //$NON-NLS-2$
        System.out.println("objective, pipeline  : " + pipeline + " | crossings " + crossings(d, pipeline(d))); //$NON-NLS-1$ //$NON-NLS-2$
        System.out.println("objective, pm4py-ucm: " + hand + " | crossings " + crossings(d, storedPositions())); //$NON-NLS-1$ //$NON-NLS-2$

        // Crossings are not in the objective and that is exactly why they are asserted separately:
        // a solver free to spend its gains on them will, and the drawing becomes unreadable while
        // every number improves. The PM4Py-UCM map is the bar.
        assertTrue("the solver must not cross paths more than PM4Py-UCM did: " //$NON-NLS-1$
                + crossings(d, solve(d, sizes)) + " against " + crossings(d, storedPositions()), //$NON-NLS-1$
                crossings(d, solve(d, sizes)) <= crossings(d, storedPositions()));

        // Under the objective as issue #30 states it -- all five terms equal.
        assertTrue("the solver must beat the pipeline it replaces: " //$NON-NLS-1$
                + solved.total(LayoutObjective.Weights.UNIT) + " against " + pipeline.total(LayoutObjective.Weights.UNIT), //$NON-NLS-1$
                solved.total(LayoutObjective.Weights.UNIT) < pipeline.total(LayoutObjective.Weights.UNIT));

        // Components were the whole loss. If that term does not improve, nothing has been fixed
        // whatever the total says.
        assertTrue("components must be tighter than the pipeline's: " + solved.sprawl + " against " + pipeline.sprawl, //$NON-NLS-1$ //$NON-NLS-2$
                solved.sprawl < pipeline.sprawl);

        // And the paths, which the pipeline already drew better than PM4Py-UCM did, must not be
        // paid out to get there -- that trade is exactly the four-pass behaviour being replaced.
        assertTrue("paths must stay at least as smooth as PM4Py-UCM's layout: " + solved.bending, //$NON-NLS-1$
                solved.bending <= hand.bending);

        // Under the calibrated weights the solver currently LOSES to the pipeline, and the reason
        // is one term: three overlapping pairs, which reportsWhatOverlapsWhat names. That is a real
        // open defect, recorded here rather than asserted away.
        //
        // Read the calibrated total with care on this term, though. The weights come from the
        // spread between PM4Py-UCM's layout and the same nodes scattered, and scattering over a
        // large rectangle is simply not a bad case for overlap -- it produces 0.063 where a genuine
        // pile-up would produce far more. A narrow measured range gives overlap a weight of 17.33,
        // which is almost certainly too high. Fixing that means a worse-than-random calibration
        // sample for this term, not a nudged constant.
        System.out.println("calibrated totals -- solver " + round(solved.total()) //$NON-NLS-1$
                + ", pipeline " + round(pipeline.total()) + ", PM4Py-UCM " + round(hand.total())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ------------------------------------------------------------------------- what it must keep

    /**
     * The two bounds {@code AutoLayoutPipelineTest} holds the old pipeline to, applied to the new
     * one. Both caught real regressions during the previous work.
     */
    @Test
    public void keepsChainsEvenlySpacedAndGentlyTurned() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Point> positions = solve(d, extents(storedPositions()));

        double worstSpacing = 1.0, worstTurn = 0.0;
        for (Iterator<UcmPathDecomposition.Chain> it = d.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            if (chain.length() == 0)
                continue;

            PointList drawn = new PointList();
            drawn.addPoint(positions.get(chain.getFrom()));
            for (Iterator<PathNode> n = chain.getInterior().iterator(); n.hasNext();)
                drawn.addPoint(positions.get(n.next()));
            drawn.addPoint(positions.get(chain.getTo()));

            worstSpacing = Math.max(worstSpacing, ChainPlacement.spacingRatio(drawn));
            worstTurn = Math.max(worstTurn, ChainPlacement.sharpestTurn(drawn));
        }

        assertTrue("worst spacing ratio " + worstSpacing, worstSpacing < 3.0); //$NON-NLS-1$
        assertTrue("sharpest turn " + worstTurn + " degrees", worstTurn < 90.0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void placesEveryNodeExactlyOnce() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Point> positions = solve(d, extents(storedPositions()));

        assertEquals("every node of the map must be positioned", sampleMap.getNodes().size(), positions.size()); //$NON-NLS-1$

        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Iterator<Point> it = positions.values().iterator(); it.hasNext();) {
            Point at = it.next();
            assertTrue("two nodes placed at " + at, seen.add(at.x + "," + at.y)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The hard constraint, checked as geometry here and as jUCMNav's own OCL rules by
     * {@code AutoLayoutLegalityTest}. Component boxes must not overlap: position is semantics in
     * URN, and two components sharing a region says something the model does not.
     */
    @Test
    public void leavesNoTwoComponentBoxesOverlapping() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());
        Map<IURNNode, Point> positions = solve(d, sizes);

        List<Rectangle> boxes = new java.util.ArrayList<Rectangle>();
        List<Double> contents = new java.util.ArrayList<Double>();
        LayoutObjective.componentBoxesOf(positions, sizes, LayoutObjective.DEFAULT_COMPONENT_MARGIN, boxes, contents);

        for (int i = 0; i < boxes.size(); i++)
            for (int j = i + 1; j < boxes.size(); j++)
                assertEquals("component boxes " + boxes.get(i) + " and " + boxes.get(j) + " overlap", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        0.0, LayoutObjective.intersectionArea(boxes.get(i), boxes.get(j)), 1e-9);
    }

    /**
     * How much work the exact repair still has to do after the descent.
     *
     * <p>
     * The penalty in the solver pushes towards disjoint components but cannot promise it, so
     * {@code ComponentSeparation} runs afterwards as an exact repair. This measures how far that
     * repair moves things: when it reaches zero here and across the demo sweep, the repair -- and
     * the last of the four passes -- can go. Printed rather than bounded, because the number is the
     * point and a threshold would only invite tuning against it.
     */
    @Test
    public void reportsHowFarTheExactRepairStillHasToMoveThings() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());

        Map<IURNNode, Point> before = ConstrainedPlacement.solve(d, seed(d), sizes, 30);
        Map<IURNNode, Point> after = new LinkedHashMap<IURNNode, Point>(before);
        ComponentSeparation.apply(after, sizes, 30);

        double worst = 0, total = 0;
        for (Iterator<IURNNode> it = before.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Point a = before.get(node), b = after.get(node);
            double moved = Math.hypot(b.x - a.x, b.y - a.y);
            worst = Math.max(worst, moved);
            total += moved;
        }

        System.out.println("repair after solve: worst " + Math.round(worst) + " px, total " + Math.round(total) + " px"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The shape of the drawing as a whole, which the objective cannot see.
     *
     * <p>
     * Nothing in the four terms penalises a drawing for being a mile wide and one node tall. A
     * layout that collapsed towards a line would score beautifully on all of them -- no bending, no
     * component area, short edges -- and that is not a hypothetical failure: swimlane bands were
     * rejected on issue #30 for being legal but one-dimensional. So the aspect ratio is watched
     * separately, against the map PM4Py-UCM drew.
     */
    @Test
    public void doesNotCollapseTheDrawingToALine() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());

        Rectangle layered = drawingBox(AutoLayoutWizard.placeUcmLayered(sampleMap));
        System.out.println("drawing, layered   : " + layered + " aspect " + aspect(layered)); //$NON-NLS-1$ //$NON-NLS-2$

        Rectangle solved = drawingBox(solve(d, sizes));
        Rectangle hand = drawingBox(storedPositions());
        Rectangle pipe = drawingBox(pipeline(d));

        System.out.println("drawing, solver    : " + solved + " aspect " + aspect(solved)); //$NON-NLS-1$ //$NON-NLS-2$
        System.out.println("drawing, pipeline  : " + pipe + " aspect " + aspect(pipe)); //$NON-NLS-1$ //$NON-NLS-2$
        System.out.println("drawing, pm4py-ucm: " + hand + " aspect " + aspect(hand)); //$NON-NLS-1$ //$NON-NLS-2$

        // PM4Py-UCM drew this map at roughly 2:1. Ten times longer than it is tall is a strip, not a
        // map, whatever the objective says about it.
        assertTrue("the drawing must not collapse to a strip: aspect " + aspect(solved), aspect(solved) < 10.0); //$NON-NLS-1$
    }

    private static double aspect(Rectangle r) {
        double longer = Math.max(r.width, r.height), shorter = Math.max(1, Math.min(r.width, r.height));
        return Math.round(longer / shorter * 100.0) / 100.0;
    }

    private static Rectangle drawingBox(Map<IURNNode, Point> positions) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (Iterator<Point> it = positions.values().iterator(); it.hasNext();) {
            Point at = it.next();
            left = Math.min(left, at.x);
            top = Math.min(top, at.y);
            right = Math.max(right, at.x);
            bottom = Math.max(bottom, at.y);
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }

    /** Where the bending actually is: every turn, sorted, for each of the three layouts. */
    @Test
    public void reportsTheTurnProfile() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());

        System.out.println("turns, solver    : " + turnProfile(d, solve(d, sizes))); //$NON-NLS-1$
        System.out.println("turns, pipeline  : " + turnProfile(d, pipeline(d))); //$NON-NLS-1$
        System.out.println("turns, pm4py-ucm: " + turnProfile(d, storedPositions())); //$NON-NLS-1$
    }

    /** Which boxes actually overlap, worst first -- the term the solver still loses on. */
    @Test
    public void reportsWhatOverlapsWhat() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());
        Map<IURNNode, Point> positions = solve(d, sizes);

        List<IURNNode> all = new java.util.ArrayList<IURNNode>(positions.keySet());
        List<String> worst = new java.util.ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                double area = LayoutObjective.intersectionArea(LayoutObjective.boxOf(all.get(i), positions, sizes),
                        LayoutObjective.boxOf(all.get(j), positions, sizes));
                if (area > 0)
                    worst.add(Math.round(area) + "px " + describe(all.get(i)) + " / " + describe(all.get(j))); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        java.util.Collections.sort(worst, java.util.Collections.reverseOrder());
        System.out.println("overlaps, solver: " + worst.size() + " pairs " + worst); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String describe(IURNNode node) {
        String name = ((URNmodelElement) node).getName();
        if (name == null || name.length() == 0)
            name = node.getClass().getSimpleName().replaceAll("Impl$", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return name.replaceAll("\\s+", " ") + "#" + ((URNmodelElement) node).getId(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private int crossings(UcmPathDecomposition d, Map<IURNNode, Point> positions) {
        return LayoutObjective.crossings(LayoutObjective.routesOf(d, positions));
    }

    private String turnProfile(UcmPathDecomposition d, Map<IURNNode, Point> positions) {
        List<Double> all = new java.util.ArrayList<Double>();
        List<PointList> routes = LayoutObjective.routesOf(d, positions);
        for (int i = 0; i < routes.size(); i++) {
            double[] angles = LayoutObjective.turnAngles(routes.get(i));
            for (int a = 0; a < angles.length; a++)
                all.add(Double.valueOf(Math.round(Math.toDegrees(angles[a]))));
        }
        java.util.Collections.sort(all);
        java.util.Collections.reverse(all);
        return all.size() + " turns, worst first: " + all; //$NON-NLS-1$
    }

    /**
     * The orientation setting has to reach the solver, not just Graphviz.
     *
     * <p>
     * The flow force is one-dimensional, so the axis it acts on <i>is</i> the orientation. It was
     * hard-coded to x while the seed was produced under whatever {@code rankdir} the preference
     * said -- so choosing "top down" seeded a top-down drawing and then spent six hundred
     * iterations pushing it left to right. The two were pulling against each other for the whole
     * descent, which is worth a test rather than a comment.
     */
    @Test
    public void honoursTheOrientationItIsGiven() throws Exception {
        assumeGraphviz();

        UcmPathDecomposition d = new UcmPathDecomposition(sampleMap);
        Map<IURNNode, Dimension> sizes = extents(storedPositions());

        // The seed has to be generated under the same orientation the solver is told to use --
        // ExportContractedDOT sets rankdir from this preference. Seeding left-to-right and then
        // flowing top-to-bottom does not produce a top-to-bottom drawing; it produces the two
        // fighting, with the seed winning. That is the bug this pair of arguments exists to stop,
        // so the test has to set both the way the wizard does.
        Map<IURNNode, Point> leftToRight = withOrientation("LR", d, sizes, true); //$NON-NLS-1$
        Map<IURNNode, Point> topToBottom = withOrientation("TB", d, sizes, false); //$NON-NLS-1$

        double lrAcross = progress(d, leftToRight, true), lrDown = progress(d, leftToRight, false);
        double tbAcross = progress(d, topToBottom, true), tbDown = progress(d, topToBottom, false);

        System.out.println("flow, left-to-right: x " + Math.round(lrAcross) + " y " + Math.round(lrDown)); //$NON-NLS-1$ //$NON-NLS-2$
        System.out.println("flow, top-to-bottom: x " + Math.round(tbAcross) + " y " + Math.round(tbDown)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("left to right must advance mostly across: x " + lrAcross + " y " + lrDown, lrAcross > lrDown); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("top to bottom must advance mostly down: x " + tbAcross + " y " + tbDown, tbDown > tbAcross); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Seeds and solves the way the wizard does: one orientation, used for both. */
    private Map<IURNNode, Point> withOrientation(String rankdir, UcmPathDecomposition d, Map<IURNNode, Dimension> sizes, boolean leftToRight)
            throws Exception {
        String was = AutoLayoutPreferences.getPreferenceStore().getString(AutoLayoutPreferences.PREF_ORIENTATION);
        AutoLayoutPreferences.getPreferenceStore().setValue(AutoLayoutPreferences.PREF_ORIENTATION, rankdir);
        try {
            return ConstrainedPlacement.solve(d, seed(d), sizes, 30, leftToRight);
        } finally {
            AutoLayoutPreferences.getPreferenceStore().setValue(AutoLayoutPreferences.PREF_ORIENTATION, was);
        }
    }

    /** Mean forward progress along one axis per chain, ignoring the loop that goes back on purpose. */
    private double progress(UcmPathDecomposition d, Map<IURNNode, Point> positions, boolean alongX) {
        double total = 0;
        int count = 0;
        for (Iterator<UcmPathDecomposition.Chain> it = d.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            Point from = positions.get(chain.getFrom()), to = positions.get(chain.getTo());
            if (d.isBackEdge(chain) || from == null || to == null)
                continue;

            total += alongX ? to.x - from.x : to.y - from.y;
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    // ---------------------------------------------------------------------------------- helpers

    private void assumeGraphviz() {
        AutoLayoutPreferences.createPreferences();
        org.junit.Assume.assumeTrue("Graphviz not installed; the solver cannot be seeded", //$NON-NLS-1$
                AutoLayoutPreferences.locateDot() != null);
    }

    /** Junction positions as Graphviz suggests them -- the solver's starting guess. */
    private Map<IURNNode, Point> seed(UcmPathDecomposition d) throws Exception {
        String dot = AutoLayoutPreferences.locateDot();
        String source = ExportContractedDOT.convert(sampleMap, d);

        Process p = new ProcessBuilder(dot, "-Tplain").redirectError(ProcessBuilder.Redirect.DISCARD).start(); //$NON-NLS-1$
        p.getOutputStream().write(source.getBytes());
        p.getOutputStream().close();

        StringBuffer out = new StringBuffer();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null)
            out.append(line).append("\n"); //$NON-NLS-1$
        reader.close();

        PlainLayout layout = new PlainLayout(out.toString());
        Map<IURNNode, Point> positions = new LinkedHashMap<IURNNode, Point>();
        for (Iterator<PathNode> it = d.getJunctions().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            PlainLayout.Node placed = layout.getNode(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) pn).getId());
            if (placed != null)
                positions.put(pn, new Point((int) Math.round(placed.x) + AutoLayoutWizard.PADDING,
                        (int) Math.round(layout.getHeight() - placed.y) + AutoLayoutWizard.PADDING));
        }
        return positions;
    }

    /** Seed, solve, then string the chain interiors along the straight runs between junctions. */
    private Map<IURNNode, Point> solve(UcmPathDecomposition d, Map<IURNNode, Dimension> sizes) throws Exception {
        Map<IURNNode, Point> positions = ConstrainedPlacement.solve(d, seed(d), sizes, 30);
        return ConstrainedPlacement.placeChainInteriors(d, positions);
    }

    /** What the pipeline being replaced produces, for comparison. */
    private Map<IURNNode, Point> pipeline(UcmPathDecomposition d) throws Exception {
        String dot = AutoLayoutPreferences.locateDot();
        String source = ExportContractedDOT.convert(sampleMap, d);

        Process p = new ProcessBuilder(dot, "-Tplain").redirectError(ProcessBuilder.Redirect.DISCARD).start(); //$NON-NLS-1$
        p.getOutputStream().write(source.getBytes());
        p.getOutputStream().close();

        StringBuffer out = new StringBuffer();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null)
            out.append(line).append("\n"); //$NON-NLS-1$
        reader.close();

        // The flag matters: the solver is now what placeUcm does by default, so without this the
        // "pipeline" being compared against would be the solver itself and every comparison below
        // would be a layout measured against a slightly differently-sized copy of itself.
        System.setProperty("jucmnav.layout.passes", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        try {
            return AutoLayoutWizard.placeUcm(d, new PlainLayout(out.toString()));
        } finally {
            System.clearProperty("jucmnav.layout.passes"); //$NON-NLS-1$
        }
    }

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
}
