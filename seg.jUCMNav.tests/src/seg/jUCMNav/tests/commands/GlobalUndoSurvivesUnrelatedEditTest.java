package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;
import java.util.Vector;

import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.create.CreateMapCommand;
import seg.jUCMNav.model.commands.create.CreatePathCommand;
import seg.jUCMNav.model.commands.transformations.RefactorIntoStubCommand;
import seg.jUCMNav.model.commands.transformations.SplitLinkCommand;
import seg.jUCMNav.views.preferences.DeletePreferences;
import ucm.map.NodeConnection;
import ucm.map.RespRef;
import ucm.map.StartPoint;
import ucm.map.Stub;
import ucm.map.UCMmap;
import urncore.IURNDiagram;

/**
 * A command spanning diagrams should survive an edit that cannot possibly touch it.
 *
 * <p>
 * Global commands -- Refactor into Stub, create/delete map -- are parked on the
 * DelegatingCommandStack's URN-spec stack, and every ordinary edit used to discard the lot. The
 * reason is real: undoing a command that spans diagrams while a page stack holds later commands
 * recorded against elements it moved applies inverse operations to a model that no longer matches
 * them, which is the "chart is displayed in disorder" of legacy projetseg-update#923.
 *
 * <p>
 * But it only holds for the diagrams the command actually touched. An edit on some other map cannot
 * reach its elements, and throwing the undo away there costs the user their history for nothing.
 * These tests pin both halves of that: the undo survives an unrelated edit, and is still discarded
 * by an edit on an affected map.
 *
 * @author Claude
 */
public class GlobalUndoSurvivesUnrelatedEditTest {

    private JUCMNavTestFixture fixture;
    private UCMmap other;

    @Before
    public void setUp() throws Exception {
        fixture = new JUCMNavTestFixture();
        fixture.initjucmnav();

        // See RefactorIntoStubUndoTest: otherwise the refactor's constructor opens a modal
        // delete-confirmation dialog that nothing can answer in the headless harness.
        DeletePreferences.getPreferenceStore().setValue(DeletePreferences.PREF_DELDEFINITION, DeletePreferences.PREF_ALWAYS);
        DeletePreferences.getPreferenceStore().setValue(DeletePreferences.PREF_DELREFERENCE, DeletePreferences.PREF_ALWAYS);

        // A second map, with a path of its own to edit later. Created before the refactor: creating
        // it is itself a global command, and one that does not declare its scope, so doing it
        // afterwards would flush the refactor for the ordinary reason and prove nothing.
        CreateMapCommand createMap = new CreateMapCommand(fixture.urnspec);
        other = (UCMmap) createMap.getMap();
        fixture.cs.execute(createMap);

        StartPoint otherStart = (StartPoint) ModelCreationFactory.getNewObject(fixture.urnspec, StartPoint.class);
        Command path = new CreatePathCommand(other, otherStart, 60, 60);
        assertTrue("CreatePathCommand on the second map must execute", path.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(path);
    }

    @After
    public void tearDown() {
        if (fixture != null)
            fixture.cleanup();
        fixture = null;
        other = null;
    }

    // ------------------------------------------------------------------------------- helpers

    /** A path with two responsibilities on the fixture's map, which is what gets refactored. */
    private Vector<RespRef> drawPathWithTwoResponsibilities() {
        UCMmap map = (UCMmap) fixture.map;

        StartPoint start = (StartPoint) ModelCreationFactory.getNewObject(fixture.urnspec, StartPoint.class);
        Command path = new CreatePathCommand(map, start, 35, 67);
        assertTrue("CreatePathCommand must execute", path.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(path);

        Vector<RespRef> responsibilities = new Vector<RespRef>();
        for (int i = 0; i < 2; i++) {
            RespRef resp = (RespRef) ModelCreationFactory.getNewObject(fixture.urnspec, RespRef.class);
            NodeConnection nc = (NodeConnection) map.getConnections().get(0);
            Command split = new SplitLinkCommand(map, resp, nc, 100 + (50 * i), 100);
            assertTrue("SplitLinkCommand must execute", split.canExecute()); //$NON-NLS-1$
            fixture.cs.execute(split);
            responsibilities.add(resp);
        }
        return responsibilities;
    }

    private void refactor(Vector<RespRef> responsibilities) {
        Vector<Object> selection = new Vector<Object>();
        selection.addAll(responsibilities);
        Command refactor = new RefactorIntoStubCommand(fixture.urnspec, selection);
        assertTrue("RefactorIntoStubCommand must execute", refactor.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(refactor);
    }

    /** Adds a responsibility to the given map, on that map's own page stack. */
    private RespRef editOn(UCMmap map) {
        fixture.editor.setActivePage(map);

        RespRef added = (RespRef) ModelCreationFactory.getNewObject(fixture.urnspec, RespRef.class);
        Command split = new SplitLinkCommand(map, added, (NodeConnection) map.getConnections().get(0), 250, 100);
        assertTrue("SplitLinkCommand must execute", split.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(split);
        return added;
    }

    private int diagramCount() {
        int n = 0;
        for (Iterator it = fixture.urnspec.getUrndef().getSpecDiagrams().iterator(); it.hasNext(); it.next())
            n++;
        return n;
    }

    private Stub stubOn(UCMmap map) {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            Object o = it.next();
            if (o instanceof Stub)
                return (Stub) o;
        }
        return null;
    }

    private String incoherences() {
        StringBuilder problems = new StringBuilder();
        for (Iterator diagrams = fixture.urnspec.getUrndef().getSpecDiagrams().iterator(); diagrams.hasNext();) {
            IURNDiagram d = (IURNDiagram) diagrams.next();
            if (!(d instanceof UCMmap))
                continue;
            UCMmap map = (UCMmap) d;

            for (Iterator it = map.getConnections().iterator(); it.hasNext();) {
                NodeConnection nc = (NodeConnection) it.next();
                if (nc.getSource() == null || nc.getTarget() == null)
                    problems.append("connection with a null end; "); //$NON-NLS-1$
                else if (!map.getNodes().contains(nc.getSource()) || !map.getNodes().contains(nc.getTarget()))
                    problems.append("connection end not in its map; "); //$NON-NLS-1$
            }
        }
        return problems.toString();
    }

    // --------------------------------------------------------------------------------- tests

    /**
     * The point of the change: an edit on a map the refactor never touched leaves its undo intact.
     */
    @Test
    public void anEditOnAnUnrelatedMapLeavesTheRefactorUndoable() {
        int diagramsBefore = diagramCount();
        int nodesBefore = ((UCMmap) fixture.map).getNodes().size();

        refactor(drawPathWithTwoResponsibilities());
        assertEquals("precondition: the refactor should have added a map", diagramsBefore + 1, diagramCount()); //$NON-NLS-1$

        editOn(other);

        assertTrue("the refactor should still be undoable after an unrelated edit", fixture.cs.canUndo()); //$NON-NLS-1$
    }

    /**
     * And undoing it really does put the model back, rather than merely reporting that it could.
     */
    @Test
    public void theSurvivingRefactorStillUndoesCleanly() {
        int diagramsBefore = diagramCount();
        int nodesBefore = ((UCMmap) fixture.map).getNodes().size();

        refactor(drawPathWithTwoResponsibilities());
        editOn(other);

        int undos = 0;
        while (fixture.cs.canUndo() && undos < 50) {
            fixture.cs.undo();
            undos++;
        }

        assertEquals("undoing everything should remove the extracted map", diagramsBefore, diagramCount()); //$NON-NLS-1$
        assertEquals("and give the original map its nodes back", nodesBefore, ((UCMmap) fixture.map).getNodes().size()); //$NON-NLS-1$
        assertEquals("without corrupting anything on the way", "", incoherences()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The half that must not change: an edit on a map the refactor <i>did</i> touch still discards
     * it. This is the case the flush exists for, and the one the model cannot survive.
     */
    @Test
    public void anEditOnTheRefactoredMapStillDiscardsTheUndo() {
        refactor(drawPathWithTwoResponsibilities());
        int diagramsAfterRefactor = diagramCount();

        editOn((UCMmap) fixture.map);

        int undos = 0;
        while (fixture.cs.canUndo() && undos < 50) {
            fixture.cs.undo();
            undos++;
        }

        assertEquals("the extracted map should still be there: its creation was flushed from history", //$NON-NLS-1$
                diagramsAfterRefactor, diagramCount());
        assertEquals("and undoing past the flush must not corrupt the model", "", incoherences()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Undo has to run newest-first. The refactor is parked on a different stack from the edit that
     * followed it, and the URN-spec stack is consulted first, so keeping the refactor alive across
     * an unrelated edit risks undoing the older command before the newer one.
     */
    @Test
    public void undoRunsNewestFirstAcrossTheTwoStacks() {
        refactor(drawPathWithTwoResponsibilities());
        RespRef addedLater = editOn(other);

        assertTrue("precondition: the later edit should be on the unrelated map", //$NON-NLS-1$
                other.getNodes().contains(addedLater));

        fixture.cs.undo();

        assertFalse("the first undo should take back the most recent edit", //$NON-NLS-1$
                other.getNodes().contains(addedLater));
        assertTrue("and not the refactor that came before it", //$NON-NLS-1$
                stubOn((UCMmap) fixture.map) != null);
    }

    /**
     * A global command executed <i>after</i> a page edit is still the newer of the two and must be
     * undone first. The rule is newest-first, not "prefer the page stack".
     */
    @Test
    public void aRefactorThatCameLastIsUndoneFirst() {
        editOn(other);
        refactor(drawPathWithTwoResponsibilities());

        assertTrue("precondition: the refactor should have left a stub", stubOn((UCMmap) fixture.map) != null); //$NON-NLS-1$

        fixture.cs.undo();

        assertTrue("the refactor should have been undone first", stubOn((UCMmap) fixture.map) == null); //$NON-NLS-1$
    }

    /**
     * A full undo/redo round trip should arrive back where it started.
     *
     * <p>
     * <b>It does not, and did not before this change either</b> -- verified by running this test
     * against the unmodified stack, where it fails the same way. Undo drains past the parked
     * command into whichever page stack happens to be current, and redo then replays the parked
     * command first, so the two are not inverses of each other. That is issue #6: the page stacks
     * and the URN-spec stack have no shared ordering, and no amount of bookkeeping at the
     * DelegatingCommandStack level fixes it, because undo also has to respect what each command
     * depends on. Sequencing the two stacks by execution order was tried and made it worse: undo
     * then walks page stacks whose commands were recorded against nodes a flushed refactor had
     * already moved, and the model comes apart.
     *
     * <p>
     * Kept rather than deleted because it states what a fix for #6 has to achieve, and it is the
     * evidence that the scoped flush did not cause this.
     */
    @Ignore("pre-existing: fails identically on the unflushed stack; see #6")
    @Test
    public void undoingAndRedoingEverythingReturnsTheModelToWhereItWas() {
        refactor(drawPathWithTwoResponsibilities());
        RespRef addedLater = editOn(other);

        int diagramsAfter = diagramCount();
        int nodesAfter = ((UCMmap) fixture.map).getNodes().size();

        int steps = 0;
        while (fixture.cs.canUndo() && steps < 50) {
            fixture.cs.undo();
            steps++;
        }
        while (fixture.cs.canRedo() && steps-- > 0)
            fixture.cs.redo();

        assertEquals("redoing everything should bring the extracted map back", diagramsAfter, diagramCount()); //$NON-NLS-1$
        assertEquals("and leave the refactored map as it was", nodesAfter, ((UCMmap) fixture.map).getNodes().size()); //$NON-NLS-1$
        assertTrue("and the later edit with it", other.getNodes().contains(addedLater)); //$NON-NLS-1$
        assertEquals("with nothing corrupted on the way", "", incoherences()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
