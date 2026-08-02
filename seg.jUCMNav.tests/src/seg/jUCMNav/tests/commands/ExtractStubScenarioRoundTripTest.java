package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
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
import seg.jUCMNav.scenarios.ScenarioUtils;
import seg.jUCMNav.views.preferences.DeletePreferences;
import ucm.map.PathNode;
import ucm.map.RespRef;
import ucm.map.Stub;
import ucm.map.UCMmap;
import ucm.scenario.ScenarioDef;
import ucm.scenario.ScenarioGroup;
import urn.URNspec;
import urncore.IURNDiagram;
import urncore.URNmodelElement;

/**
 * Refactor into Stub, checked against what the model is <i>for</i>: its scenarios.
 *
 * <p>
 * Structural assertions -- path counts, bindings, containment -- say the result is well formed.
 * They do not say it still means the same thing. These tests do: extract a stub, re-run every
 * scenario definition, undo, run them again, and require that the responsibilities executed and
 * the warnings raised are the same at each step. A refactoring that changes any of that has
 * changed the model's behaviour, whatever its shape looks like.
 *
 * <p>
 * The model is {@code IssueTrackerSyntheticLog_variant.jucm}, mined from an event log by PM4Py-UCM
 * and contributed to the public domain by the reporter. It is a deliberately awkward shape for
 * this operation:
 *
 * <pre>
 * start -&gt; Create Ticket -&gt; Triage -&gt; AndFork -+-&gt; Assign Reviewer  -+-&gt; AndJoin -&gt; Implement Fix
 *                                              +-&gt; Assign Developer -+
 *
 *   ... -&gt; LoopJoin -&gt; Test Fix -&gt; LoopFork --redo--&gt; Fix Bug -&gt; LoopEntryGuard --enter--&gt; LoopJoin
 *                                          \--exit--&gt; Deploy Fix -&gt; Close Ticket -&gt; end
 * </pre>
 *
 * so it has true concurrency, a counted loop over an integer variable, an enumerated variant
 * selector, and five components -- each of which the extraction has to carry across without
 * changing the traversal.
 *
 * <p>
 * The selections are chosen to hit the cases that have historically gone wrong rather than to be
 * exhaustive: a straight run, a whole parallel block, a loop with its back edge, a scope that
 * swallows the path's start point, one that swallows its end, the entire map, and two nodes on
 * sibling branches that are not "between" each other at all.
 *
 * @author Claude
 */
public class ExtractStubScenarioRoundTripTest {

    private static final String SAMPLE = "IssueTrackerSyntheticLog_variant.jucm"; //$NON-NLS-1$

    private UCMNavMultiPageEditor editor;
    private URNspec urn;
    private UCMmap map;

    @Before
    public void setUp() throws Exception {
        // See RefactorIntoStubUndoTest: without these the refactor's constructor opens a modal
        // delete-confirmation dialog that nothing can answer in the headless harness.
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
        file.create(ExtractStubScenarioRoundTripTest.class.getResourceAsStream(SAMPLE), false, null);

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
        assertTrue("the sample should define scenarios", !scenarios().isEmpty()); //$NON-NLS-1$
    }

    @After
    public void tearDown() {
        if (editor != null)
            editor.closeEditor(false);
        editor = null;
        urn = null;
        map = null;
    }

    // ------------------------------------------------------------------ model access

    private List<ScenarioDef> scenarios() {
        List<ScenarioDef> defs = new ArrayList<ScenarioDef>();
        for (Iterator it = urn.getUcmspec().getScenarioGroups().iterator(); it.hasNext();) {
            ScenarioGroup g = (ScenarioGroup) it.next();
            for (Iterator s = g.getScenarios().iterator(); s.hasNext();)
                defs.add((ScenarioDef) s.next());
        }
        return defs;
    }

    private PathNode node(String id) {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (id.equals(((URNmodelElement) pn).getId()))
                return pn;
        }
        throw new IllegalArgumentException("the sample has no node " + id); //$NON-NLS-1$
    }

    /**
     * Every responsibility reference in the spec, wherever it now lives. Nodes are moved by the
     * extraction rather than copied, so these are the same objects before and after -- which is
     * what makes a hit count comparable across the refactor at all.
     */
    private List<RespRef> allResponsibilityRefs() {
        List<RespRef> refs = new ArrayList<RespRef>();
        for (Iterator it = urn.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (!(d instanceof UCMmap))
                continue;
            for (Iterator n = ((UCMmap) d).getNodes().iterator(); n.hasNext();) {
                Object o = n.next();
                if (o instanceof RespRef)
                    refs.add((RespRef) o);
            }
        }
        return refs;
    }

    private Stub theStub() {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            Object o = it.next();
            if (o instanceof Stub)
                return (Stub) o;
        }
        return null;
    }

    // --------------------------------------------------------------- running scenarios

    /**
     * Runs every scenario and reports what happened, in two parts.
     *
     * <p>
     * {@code [0]} is the <b>behaviour</b>: how many warnings each scenario raised and how many
     * times each responsibility executed, keyed by the responsibility's own name so it survives
     * being moved to another map. This must be identical before and after an extraction -- it is
     * the definition of "the scenarios still work".
     *
     * <p>
     * {@code [1]} is the <b>detail</b>: the warning text itself. Required to match only across an
     * undo, where the model is supposed to be restored exactly, since a warning that names an
     * element can legitimately read differently once that element sits behind a stub.
     */
    private String[] runScenarios() {
        StringBuilder behaviour = new StringBuilder();
        StringBuilder detail = new StringBuilder();

        for (Iterator<ScenarioDef> it = scenarios().iterator(); it.hasNext();) {
            ScenarioDef def = it.next();
            Vector warnings = ScenarioUtils.traverseWarn(def, null);

            behaviour.append(def.getName()).append(" warnings=") //$NON-NLS-1$
                    .append(warnings == null ? "aborted" : Integer.toString(warnings.size())); //$NON-NLS-1$

            List<String> hits = new ArrayList<String>();
            for (Iterator<RespRef> r = allResponsibilityRefs().iterator(); r.hasNext();) {
                RespRef ref = r.next();
                String name = ref.getRespDef() == null ? ref.getName() : ref.getRespDef().getName();
                hits.add(name + "=" + ScenarioUtils.getTraversalHitCount(ref)); //$NON-NLS-1$
            }
            Collections.sort(hits);
            behaviour.append(' ').append(hits).append('\n');

            List<String> messages = new ArrayList<String>();
            if (warnings != null)
                for (Iterator w = warnings.iterator(); w.hasNext();)
                    messages.add(String.valueOf(w.next()));
            Collections.sort(messages);
            detail.append(def.getName()).append(' ').append(messages).append('\n');
        }

        return new String[] { behaviour.toString(), detail.toString() };
    }

    // ------------------------------------------------------------------- the round trip

    /**
     * Extract the given nodes into a stub, re-run the scenarios, undo, run them again.
     *
     * Every assertion here has to hold for any selection on any model; nothing is specific to the
     * shape being extracted, so a new case is one line at the bottom of this file.
     */
    private void roundTrip(String what, String[] ids) {
        String[] before = runScenarios();

        Vector<Object> selection = new Vector<Object>();
        for (int i = 0; i < ids.length; i++)
            selection.add(node(ids[i]));

        StubExtractionScope scope = new StubExtractionScope(selection);
        int expectedIn = scope.getInbound().size() + scope.getOwnStarts().size();
        int expectedOut = scope.getOutbound().size() + scope.getOwnEnds().size();

        Command refactor = new RefactorIntoStubCommand(urn, selection);
        assertTrue(what + ": the refactor should be executable", refactor.canExecute()); //$NON-NLS-1$
        editor.getDelegatingCommandStack().execute(refactor);

        Stub stub = theStub();
        assertTrue(what + ": the refactor should leave a stub behind", stub != null); //$NON-NLS-1$
        assertTrue(what + ": the stub must have a way in", stub.getPred().size() >= 1); //$NON-NLS-1$
        assertTrue(what + ": the stub must have a way out", stub.getSucc().size() >= 1); //$NON-NLS-1$
        assertEquals(what + ": stub paths should be the boundary plus the extremities it swallowed", //$NON-NLS-1$
                "in=" + expectedIn + " out=" + expectedOut, //$NON-NLS-1$ //$NON-NLS-2$
                "in=" + stub.getPred().size() + " out=" + stub.getSucc().size()); //$NON-NLS-1$ //$NON-NLS-2$

        String[] extracted = runScenarios();
        assertEquals(what + ": extracting a stub must not change what the scenarios do\nwarnings before:\n" //$NON-NLS-1$
                + before[1] + "warnings after:\n" + extracted[1], //$NON-NLS-1$
                before[0], extracted[0]);

        assertTrue(what + ": the refactor must be undoable", editor.getDelegatingCommandStack().canUndo()); //$NON-NLS-1$
        editor.getDelegatingCommandStack().undo();

        assertTrue(what + ": undo should have removed the stub", theStub() == null); //$NON-NLS-1$

        String[] restored = runScenarios();
        assertEquals(what + ": undo must restore what the scenarios do", before[0], restored[0]); //$NON-NLS-1$
        assertEquals(what + ": undo must restore the warnings exactly", before[1], restored[1]); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------- the cases

    /** The baseline itself has to be stable, or every comparison below is meaningless. */
    @Test
    public void runningTheScenariosTwiceGivesTheSameAnswer() {
        String[] first = runScenarios();
        String[] second = runScenarios();

        assertEquals("the traversal must be deterministic", first[0], second[0]); //$NON-NLS-1$
        assertEquals("and so must its warnings", first[1], second[1]); //$NON-NLS-1$
    }

    /** Two adjacent responsibilities in the middle of the path: the simplest possible extraction. */
    @Test
    public void aStraightRunOfTwoResponsibilities() {
        roundTrip("Create Ticket .. Triage", new String[] { "4", "5" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A single responsibility, alone inside its component. */
    @Test
    public void aSingleResponsibilityInsideAComponent() {
        roundTrip("Test Fix alone", new String[] { "13" }); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The whole concurrent block. The closure pulls in both branches, so the stub stands for real
     * concurrency and the AND-join inside it has to still synchronise after the move.
     */
    @Test
    public void theWholeParallelBlock() {
        roundTrip("AndFork .. AndJoin", new String[] { "6", "7" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The loop body, without its back edge: LoopJoin through the fork that decides to repeat. */
    @Test
    public void theLoopBody() {
        roundTrip("LoopJoin .. LoopFork", new String[] { "11", "12" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The loop <i>with</i> its back edge. Each of the two selected nodes is downstream of the
     * other, so the closure swallows the cycle whole -- the case where "everything between the
     * extremities" has to mean something on a graph that is not a tree.
     */
    @Test
    public void theLoopIncludingItsBackEdge() {
        roundTrip("LoopJoin .. LoopEntryGuard", new String[] { "11", "29" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A scope that swallows the path's start point -- the case that left the parent map with no
     * way in at all. The scenario's own start point moves to the plug-in map with it, so this also
     * checks that a scenario anchored on an extracted start point still runs.
     */
    @Test
    public void aScopeThatSwallowsTheStartPoint() {
        roundTrip("start .. Triage", new String[] { "2", "5" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** And the mirror image, swallowing the end point the scenarios require to be reached. */
    @Test
    public void aScopeThatSwallowsTheEndPoint() {
        roundTrip("Close Ticket .. end", new String[] { "16", "3" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Everything: the parent map is reduced to a start point, a stub and an end point. */
    @Test
    public void theEntireMap() {
        List<String> ids = new ArrayList<String>();
        for (Iterator it = map.getNodes().iterator(); it.hasNext();)
            ids.add(((URNmodelElement) it.next()).getId());

        roundTrip("the entire map", ids.toArray(new String[ids.size()])); //$NON-NLS-1$
    }

    /**
     * Two responsibilities on sibling branches of the AND-fork. Neither is between the other, so
     * the extraction takes exactly those two nodes as separate fragments and the stub gets two
     * in-paths and two out-paths -- one per branch, still concurrent, still joined afterwards.
     *
     * <p>
     * This is the open design question from #29 in test form. The shape is unusual, but the
     * scenarios are the arbiter: if the traversal still executes the same responsibilities the same
     * number of times, the extraction is defensible whatever it looks like on screen.
     */
    @Test
    public void twoNodesOnSiblingBranches() {
        roundTrip("Assign Reviewer + Assign Developer", new String[] { "8", "9" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
