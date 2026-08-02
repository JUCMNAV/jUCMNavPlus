package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.gef.commands.Command;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.model.commands.transformations.RefactorIntoStubCommand;
import seg.jUCMNav.model.util.StubExtractionScope;
import seg.jUCMNav.views.preferences.DeletePreferences;
import ucm.map.EndPoint;
import ucm.map.InBinding;
import ucm.map.NodeConnection;
import ucm.map.OutBinding;
import ucm.map.PathNode;
import ucm.map.PluginBinding;
import ucm.map.StartPoint;
import ucm.map.Stub;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;
import urncore.URNmodelElement;

/**
 * Refactor into Stub on the reporter's own model (ExtractStub.jucm), which is what finally made
 * the reported extra paths reproducible -- synthetic fork/join models built in
 * {@link RefactorIntoStubLoopTest} do not trigger it, because their branches are too thin.
 *
 * <p>
 * The sample is:
 *
 * <pre>
 * Start -&gt; EP15 -&gt; OrFork36 -+-&gt; EP23 -&gt; RespB58 ------------+-&gt; OrJoin48 -&gt; RespC60 -&gt; End
 *                            +-&gt; EP37 -&gt; RespA56 -&gt; EP49 ---+
 * </pre>
 *
 * <p>
 * Both reported selections have boundaries that can be counted by hand, so the expected stub shape
 * is not a matter of opinion:
 *
 * <ul>
 * <li><b>whole block</b> (36, 23, 37, 56, 58, 49, 48): one connection enters (15-&gt;36) and one
 * leaves (48-&gt;60), so the stub should have 1 in-path and 1 out-path;</li>
 * <li><b>fork and join only</b> (36, 48): three enter (15-&gt;36, 58-&gt;48, 49-&gt;48) and three
 * leave (36-&gt;23, 36-&gt;37, 48-&gt;60), so the stub should have 3 and 3. The branch contents stay
 * on the parent map, which is legitimate -- they are simply not part of the selection.</li>
 * </ul>
 *
 * Each expectation is still computed from the model rather than hard-coded, so the test states the
 * rule; the hand counts above are only there to show the rule gives an unsurprising answer here.
 *
 * @author Claude
 */
public class ExtractStubFromSampleTest {

    private static final String SAMPLE = "ExtractStub.jucm"; //$NON-NLS-1$

    private UCMNavMultiPageEditor editor;
    private URNspec urn;
    private UCMmap map;

    @Before
    public void setUp() throws Exception {
        DeletePreferences.getPreferenceStore().setValue(DeletePreferences.PREF_DELDEFINITION, DeletePreferences.PREF_ALWAYS);
        DeletePreferences.getPreferenceStore().setValue(DeletePreferences.PREF_DELREFERENCE, DeletePreferences.PREF_ALWAYS);

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject("jUCMNav-tests"); //$NON-NLS-1$
        if (!project.exists())
            project.create(null);
        if (!project.isOpen())
            project.open(null);

        IFile file = project.getFile(SAMPLE);
        if (file.exists())
            file.delete(true, false, null);
        file.create(ExtractStubFromSampleTest.class.getResourceAsStream(SAMPLE), false, null);

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(file.getName());
        editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(file), desc.getId());

        urn = editor.getModel();
        for (Iterator it = urn.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                map = (UCMmap) d;
        }
        assertTrue("the sample should contain a UCM map", map != null); //$NON-NLS-1$
    }

    @After
    public void tearDown() {
        if (editor != null)
            editor.closeEditor(false);
        editor = null;
        urn = null;
        map = null;
    }

    private PathNode node(String id) {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (id.equals(((URNmodelElement) pn).getId()))
                return pn;
        }
        return null;
    }

    private int inBoundary(Set<PathNode> selection) {
        int count = 0;
        for (Iterator it = map.getConnections().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (selection.contains(nc.getTarget()) && !selection.contains(nc.getSource()))
                count++;
        }
        return count;
    }

    private int outBoundary(Set<PathNode> selection) {
        int count = 0;
        for (Iterator it = map.getConnections().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (selection.contains(nc.getSource()) && !selection.contains(nc.getTarget()))
                count++;
        }
        return count;
    }

    private Stub theStub() {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (pn instanceof Stub)
                return (Stub) pn;
        }
        return null;
    }

    private int startPointsOnParentMap() {
        int count = 0;
        for (Iterator it = map.getNodes().iterator(); it.hasNext();)
            if (it.next() instanceof StartPoint)
                count++;
        return count;
    }

    private int endPointsOnParentMap() {
        int count = 0;
        for (Iterator it = map.getNodes().iterator(); it.hasNext();)
            if (it.next() instanceof EndPoint)
                count++;
        return count;
    }

    private void refactorAndCheck(Set<PathNode> selection, String what) {
        // The stub's paths must equal the boundary of what is actually extracted, which is the
        // closure of the selection -- everything between its extremities -- not the literal set of
        // nodes the user clicked. Computing it from StubExtractionScope states that rule rather
        // than restating one instance of it.
        //
        // Plus one path for each start or end point the scope swallowed: nothing crosses the
        // boundary there, but the parent map still lost a way in or out and has to get it back.
        StubExtractionScope extracted = new StubExtractionScope(selection);
        int expectedIn = inBoundary(extracted.getScope()) + extracted.getOwnStarts().size();
        int expectedOut = outBoundary(extracted.getScope()) + extracted.getOwnEnds().size();

        Vector<Object> arg = new Vector<Object>();
        arg.addAll(selection);
        Command refactor = new RefactorIntoStubCommand(urn, arg);
        assertTrue("RefactorIntoStubCommand must execute", refactor.canExecute()); //$NON-NLS-1$
        editor.getDelegatingCommandStack().execute(refactor);

        Stub stub = theStub();
        assertTrue("the refactor should have left a stub on the parent map", stub != null); //$NON-NLS-1$

        assertEquals(what + ": the stub's path counts should equal the selection boundary", //$NON-NLS-1$
                "in=" + expectedIn + " out=" + expectedOut, //$NON-NLS-1$ //$NON-NLS-2$
                "in=" + stub.getPred().size() + " out=" + stub.getSucc().size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The first reported case: select from before the fork to after the join. */
    @Test
    public void extractingTheWholeForkJoinBlockGivesOneInAndOneOut() {
        Set<PathNode> selection = new HashSet<PathNode>();
        String[] ids = { "36", "23", "37", "56", "58", "49", "48" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        for (int i = 0; i < ids.length; i++) {
            PathNode pn = node(ids[i]);
            assertTrue("sample should contain node " + ids[i], pn != null); //$NON-NLS-1$
            selection.add(pn);
        }

        assertEquals("sanity: the whole block has a single entry", 1, inBoundary(selection)); //$NON-NLS-1$
        assertEquals("sanity: the whole block has a single exit", 1, outBoundary(selection)); //$NON-NLS-1$

        refactorAndCheck(selection, "whole block"); //$NON-NLS-1$
    }

    /**
     * The second reported case: select only the fork and the join.
     *
     * The literal selection has three entries and three exits, but the extraction closes over
     * everything between the two, so what actually moves is the whole block and the stub gets one
     * in-path and one out-path. Anything less would leave a stub standing for a block whose
     * branches stayed on the parent map -- the "plug-in map loses equivalence" half of #29.
     */
    @Test
    public void extractingOnlyTheForkAndJoinExtractsTheWholeBlock() {
        Set<PathNode> selection = new HashSet<PathNode>();
        PathNode fork = node("36"); //$NON-NLS-1$
        PathNode join = node("48"); //$NON-NLS-1$
        assertTrue("sample should contain the fork and the join", fork != null && join != null); //$NON-NLS-1$
        selection.add(fork);
        selection.add(join);

        assertEquals("sanity: the literal selection has three entries", 3, inBoundary(selection)); //$NON-NLS-1$
        assertEquals("sanity: but its closure is the whole block", //$NON-NLS-1$
                7, new StubExtractionScope(selection).getScope().size());

        refactorAndCheck(selection, "fork and join only"); //$NON-NLS-1$
    }

    /**
     * The third reported case: select the start point and responsibility C, so the extraction
     * swallows the beginning of the path.
     *
     * <p>
     * Moving that start point onto the plug-in map left the parent map holding a stub with no
     * in-path and no start point anywhere: a path with no beginning, which no amount of later
     * editing can repair.
     *
     * <p>
     * A start point is the map's way in rather than part of its body, so it stays where it is and
     * the connection leaving it becomes the boundary. What this test adds to the counts asserted in
     * {@link #refactorAndCheck} is that the parent map is still a well-formed map afterwards.
     */
    @Test
    public void extractingTheStartPointLeavesTheParentMapWithAWayIn() {
        Set<PathNode> selection = new HashSet<PathNode>();
        PathNode start = node("14"); //$NON-NLS-1$
        PathNode respC = node("60"); //$NON-NLS-1$
        assertTrue("sample should contain the start point and RespC", start != null && respC != null); //$NON-NLS-1$
        selection.add(start);
        selection.add(respC);

        assertEquals("sanity: the sample starts with one start point", 1, startPointsOnParentMap()); //$NON-NLS-1$
        assertFalse("sanity: the start point is not part of what moves", //$NON-NLS-1$
                new StubExtractionScope(selection).getScope().contains(start));

        refactorAndCheck(selection, "start point and RespC"); //$NON-NLS-1$

        Stub stub = theStub();
        assertTrue("the stub must have a way in", stub.getPred().size() >= 1); //$NON-NLS-1$
        assertTrue("the stub must have a way out", stub.getSucc().size() >= 1); //$NON-NLS-1$
        assertEquals("the parent map must still have a start point", 1, startPointsOnParentMap()); //$NON-NLS-1$

        // ...and it must lead to the stub, not sit stranded somewhere on the map.
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (pn instanceof StartPoint) {
                assertEquals("the parent map's start point should have a single successor", //$NON-NLS-1$
                        1, pn.getSucc().size());
                assertEquals("and it should lead into the stub", //$NON-NLS-1$
                        stub, ((NodeConnection) pn.getSucc().get(0)).getTarget());
            }
        }
    }

    /**
     * The mirror image: swallow the end point and the parent map must keep a way out.
     *
     * Worth its own test rather than trusting symmetry -- the two directions are separate code,
     * and an extraction that ends nowhere is just as broken as one that starts nowhere.
     */
    @Test
    public void extractingTheEndPointLeavesTheParentMapWithAWayOut() {
        Set<PathNode> selection = new HashSet<PathNode>();
        selection.add(node("58")); //$NON-NLS-1$
        selection.add(node("16")); //$NON-NLS-1$

        assertEquals("sanity: the sample ends with one end point", 1, endPointsOnParentMap()); //$NON-NLS-1$
        assertFalse("sanity: the end point is not part of what moves", //$NON-NLS-1$
                new StubExtractionScope(selection).getScope().contains(node("16"))); //$NON-NLS-1$

        refactorAndCheck(selection, "RespB and the end point"); //$NON-NLS-1$

        Stub stub = theStub();
        assertTrue("the stub must have a way in", stub.getPred().size() >= 1); //$NON-NLS-1$
        assertTrue("the stub must have a way out", stub.getSucc().size() >= 1); //$NON-NLS-1$
        assertEquals("the parent map must still have an end point", 1, endPointsOnParentMap()); //$NON-NLS-1$
    }

    /**
     * The whole map, extracted. Both extremities move at once, so the parent map is left with
     * nothing but a start point, the stub and an end point -- the smallest map that still means
     * what the original meant.
     */
    @Test
    public void extractingEverythingLeavesAWellFormedParentMap() {
        Set<PathNode> selection = new HashSet<PathNode>();
        for (Iterator it = map.getNodes().iterator(); it.hasNext();)
            selection.add((PathNode) it.next());

        refactorAndCheck(selection, "the whole map"); //$NON-NLS-1$

        Stub stub = theStub();
        assertEquals("one way in", 1, stub.getPred().size()); //$NON-NLS-1$
        assertEquals("one way out", 1, stub.getSucc().size()); //$NON-NLS-1$
        assertEquals("start point, stub, end point and nothing else", 3, map.getNodes().size()); //$NON-NLS-1$
        assertEquals("exactly one start point", 1, startPointsOnParentMap()); //$NON-NLS-1$
        assertEquals("exactly one end point", 1, endPointsOnParentMap()); //$NON-NLS-1$
    }

    /**
     * Every path the stub gained must be bound to an endpoint of the plug-in map, whichever way it
     * arose -- boundary crossing or anchored extremity. An unbound path is a stub that silently
     * goes nowhere at traversal time, which is harder to notice than a missing one.
     */
    @Test
    public void everyStubPathIsBoundToThePlugin() {
        Set<PathNode> selection = new HashSet<PathNode>();
        selection.add(node("14")); //$NON-NLS-1$
        selection.add(node("60")); //$NON-NLS-1$

        refactorAndCheck(selection, "start point and RespC"); //$NON-NLS-1$

        Stub stub = theStub();
        assertEquals("the stub should have exactly one plug-in", 1, stub.getBindings().size()); //$NON-NLS-1$
        PluginBinding binding = (PluginBinding) stub.getBindings().get(0);

        assertEquals("every in-path should be bound", stub.getPred().size(), binding.getIn().size()); //$NON-NLS-1$
        assertEquals("every out-path should be bound", stub.getSucc().size(), binding.getOut().size()); //$NON-NLS-1$

        for (Iterator it = binding.getIn().iterator(); it.hasNext();) {
            InBinding in = (InBinding) it.next();
            assertTrue("an in-binding must name a stub entry", stub.getPred().contains(in.getStubEntry())); //$NON-NLS-1$
            assertTrue("and a start point of the plug-in map", //$NON-NLS-1$
                    binding.getPlugin().getNodes().contains(in.getStartPoint()));
        }
        for (Iterator it = binding.getOut().iterator(); it.hasNext();) {
            OutBinding out = (OutBinding) it.next();
            assertTrue("an out-binding must name a stub exit", stub.getSucc().contains(out.getStubExit())); //$NON-NLS-1$
            assertTrue("and an end point of the plug-in map", //$NON-NLS-1$
                    binding.getPlugin().getNodes().contains(out.getEndPoint()));
        }
    }
}
