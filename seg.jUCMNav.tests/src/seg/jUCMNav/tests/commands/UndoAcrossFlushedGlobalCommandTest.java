package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;
import java.util.Vector;

import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.create.CreatePathCommand;
import seg.jUCMNav.model.commands.transformations.DividePathCommand;
import seg.jUCMNav.model.commands.transformations.RefactorIntoStubCommand;
import seg.jUCMNav.model.commands.transformations.SplitLinkCommand;
import seg.jUCMNav.views.preferences.DeletePreferences;
import ucm.map.EndPoint;
import ucm.map.NodeConnection;
import ucm.map.OrFork;
import ucm.map.OrJoin;
import ucm.map.PathNode;
import ucm.map.RespRef;
import ucm.map.StartPoint;
import ucm.map.UCMmap;
import urncore.IURNDiagram;

/**
 * Undoing past a global command that has been flushed from history must not corrupt the model
 * (#28, the part of legacy projetseg-update#923 that survived the earlier fixes).
 *
 * <p>
 * A command spanning diagrams -- Refactor into Stub, create/delete map -- executes on the
 * DelegatingCommandStack's URN-spec stack rather than a page stack. The first ordinary edit
 * afterwards calls {@code flushURNspecStack()}, which drops it from history for good, enforcing
 * the rule "you cannot undo a create/delete-map command once another action has been performed".
 *
 * <p>
 * The page stack is not flushed with it, and still holds the commands that built the nodes the
 * refactor deleted and moved to the extracted map. Undo then continues happily into them, applying
 * inverse operations against a model that no longer matches what they were recorded against.
 *
 * <p>
 * This test does not assert that the model is fully restored -- it cannot be, since the flush is
 * deliberate. It asserts only that whatever remains is <i>structurally coherent</i>: every
 * connection joins two nodes that are actually present in the same diagram, and every node's
 * connections are ones the diagram knows about. A dangling connection or an orphaned node is the
 * "chart is displayed in disorder" of the original report.
 *
 * @author Claude
 */
public class UndoAcrossFlushedGlobalCommandTest {

    private JUCMNavTestFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new JUCMNavTestFixture();
        fixture.initjucmnav();

        // See RefactorIntoStubUndoTest: otherwise the refactor's constructor opens a modal
        // delete-confirmation dialog that nothing can answer in the headless harness.
        DeletePreferences.getPreferenceStore().setValue(DeletePreferences.PREF_DELDEFINITION, DeletePreferences.PREF_ALWAYS);
        DeletePreferences.getPreferenceStore().setValue(DeletePreferences.PREF_DELREFERENCE, DeletePreferences.PREF_ALWAYS);
    }

    @After
    public void tearDown() {
        if (fixture != null)
            fixture.cleanup();
        fixture = null;
    }

    /**
     * Describes every structural inconsistency across all diagrams, or "" when there are none.
     * Returned as text so a failure names the actual damage rather than just a count.
     */
    private String incoherences() {
        StringBuilder problems = new StringBuilder();

        for (Iterator diagrams = fixture.urnspec.getUrndef().getSpecDiagrams().iterator(); diagrams.hasNext();) {
            IURNDiagram d = (IURNDiagram) diagrams.next();
            if (!(d instanceof UCMmap))
                continue;
            UCMmap map = (UCMmap) d;

            for (Iterator it = map.getConnections().iterator(); it.hasNext();) {
                NodeConnection nc = (NodeConnection) it.next();
                if (nc.getSource() == null || nc.getTarget() == null) {
                    problems.append("connection with a null end; ");
                    continue;
                }
                if (!map.getNodes().contains(nc.getSource()))
                    problems.append("connection source not in its map; ");
                if (!map.getNodes().contains(nc.getTarget()))
                    problems.append("connection target not in its map; ");
            }

            for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
                PathNode pn = (PathNode) it.next();
                if (pn.getDiagram() != map)
                    problems.append("node whose getDiagram() is not the map holding it; ");
                for (Iterator s = pn.getSucc().iterator(); s.hasNext();)
                    if (!map.getConnections().contains(s.next()))
                        problems.append("node succ connection missing from the map; ");
                for (Iterator p = pn.getPred().iterator(); p.hasNext();)
                    if (!map.getConnections().contains(p.next()))
                        problems.append("node pred connection missing from the map; ");
            }
        }

        return problems.toString();
    }

    @Test
    public void undoingPastAFlushedRefactorLeavesACoherentModel() {
        UCMmap map = (UCMmap) fixture.map;

        StartPoint start = (StartPoint) ModelCreationFactory.getNewObject(fixture.urnspec, StartPoint.class);
        Command cmd = new CreatePathCommand(map, start, 35, 67);
        assertTrue("CreatePathCommand must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        Vector<RespRef> responsibilities = new Vector<RespRef>();
        for (int i = 0; i < 2; i++) {
            RespRef resp = (RespRef) ModelCreationFactory.getNewObject(fixture.urnspec, RespRef.class);
            NodeConnection nc = (NodeConnection) map.getConnections().get(0);
            Command split = new SplitLinkCommand(map, resp, nc, 100 + (50 * i), 100);
            assertTrue("SplitLinkCommand must execute", split.canExecute()); //$NON-NLS-1$
            fixture.cs.execute(split);
            responsibilities.add(resp);
        }

        assertEquals("precondition: the model starts coherent", "", incoherences()); //$NON-NLS-1$ //$NON-NLS-2$

        // Refactor -- lands on the URN-spec stack, deletes the two responsibilities from this map.
        Vector<Object> selection = new Vector<Object>();
        selection.addAll(responsibilities);
        Command refactor = new RefactorIntoStubCommand(fixture.urnspec, selection);
        assertTrue("RefactorIntoStubCommand must execute", refactor.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(refactor);

        // An ordinary edit: this is what calls flushURNspecStack() and discards the refactor.
        RespRef added = (RespRef) ModelCreationFactory.getNewObject(fixture.urnspec, RespRef.class);
        NodeConnection nc = (NodeConnection) map.getConnections().get(0);
        Command split = new SplitLinkCommand(map, added, nc, 250, 100);
        assertTrue("SplitLinkCommand must execute", split.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(split);

        // Drain the undo history. Bounded: a stuck command would otherwise spin forever.
        int undos = 0;
        while (fixture.cs.canUndo() && undos < 50) {
            fixture.cs.undo();
            undos++;
        }

        assertEquals("undoing past the flushed refactor must not corrupt the model", //$NON-NLS-1$
                "", incoherences()); //$NON-NLS-1$
    }

    /**
     * The same question for a fork/join block, which severs the path in more places than a plain
     * sequence does and so leaves more dangling ends for undo to trip over. This is the shape that
     * produces the spurious stub loop of #29, making it the likeliest candidate for structural
     * damage as well.
     */
    @Test
    public void undoingPastAFlushedForkJoinRefactorLeavesACoherentModel() {
        UCMmap map = (UCMmap) fixture.map;

        StartPoint start = (StartPoint) ModelCreationFactory.getNewObject(fixture.urnspec, StartPoint.class);
        Command cmd = new CreatePathCommand(map, start, 100, 100);
        assertTrue("CreatePathCommand must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        Vector<EndPoint> endsBefore = endPoints(map);
        OrFork fork = (OrFork) ModelCreationFactory.getNewObject(fixture.urnspec, OrFork.class);
        cmd = new DividePathCommand(fork, (NodeConnection) map.getConnections().get(0), 200, 100);
        assertTrue("DividePathCommand(fork) must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        Vector<EndPoint> spares = endPoints(map);
        spares.removeAll(endsBefore);
        assertEquals("the fork should have spawned exactly one spare branch", 1, spares.size()); //$NON-NLS-1$
        EndPoint spare = spares.get(0);

        OrJoin join = (OrJoin) ModelCreationFactory.getNewObject(fixture.urnspec, OrJoin.class);
        NodeConnection afterFork = null;
        for (Iterator it = fork.getSucc().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (nc.getTarget() != spare)
                afterFork = nc;
        }
        cmd = new DividePathCommand(join, afterFork, 400, 100, spare);
        assertTrue("DividePathCommand(join) must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        assertEquals("precondition: the model starts coherent", "", incoherences()); //$NON-NLS-1$ //$NON-NLS-2$

        Vector<Object> selection = new Vector<Object>();
        selection.add(fork);
        selection.add(join);
        Command refactor = new RefactorIntoStubCommand(fixture.urnspec, selection);
        assertTrue("RefactorIntoStubCommand must execute", refactor.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(refactor);

        RespRef added = (RespRef) ModelCreationFactory.getNewObject(fixture.urnspec, RespRef.class);
        Command split = new SplitLinkCommand(map, added, (NodeConnection) map.getConnections().get(0), 550, 100);
        assertTrue("SplitLinkCommand must execute", split.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(split);

        int undos = 0;
        while (fixture.cs.canUndo() && undos < 50) {
            fixture.cs.undo();
            undos++;
        }

        assertEquals("undoing past a flushed fork/join refactor must not corrupt the model", //$NON-NLS-1$
                "", incoherences()); //$NON-NLS-1$
    }

    private Vector<EndPoint> endPoints(UCMmap map) {
        Vector<EndPoint> ends = new Vector<EndPoint>();
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (pn instanceof EndPoint)
                ends.add((EndPoint) pn);
        }
        return ends;
    }
}
