package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;
import java.util.Vector;

import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.create.CreatePathCommand;
import seg.jUCMNav.model.commands.transformations.DividePathCommand;
import seg.jUCMNav.model.commands.transformations.RefactorIntoStubCommand;
import seg.jUCMNav.views.preferences.DeletePreferences;
import ucm.map.EndPoint;
import ucm.map.NodeConnection;
import ucm.map.OrFork;
import ucm.map.OrJoin;
import ucm.map.PathNode;
import ucm.map.StartPoint;
import ucm.map.Stub;
import ucm.map.UCMmap;
import urncore.IURNNode;

/**
 * Refactoring an OR-fork / OR-join block into a stub leaves a spurious extra path.
 *
 * <p>
 * Reported behaviour: select an OR-fork, its branches and the OR-join that recombines them, invoke
 * Refactor into Stub, and instead of a stub with one in-path and one out-path you get an extra
 * path that leaves the stub and comes back into it.
 *
 * <p>
 * <b>Reproduced.</b> This test builds start -&gt; fork -&gt; {A, B} -&gt; join -&gt; end and
 * refactors the fork and join. The stub comes out with
 * {@code loopingOutPaths=1 in=2 out=2} where {@code loopingOutPaths=0 in=1 out=1} is wanted. The
 * loop is several hops long -- out of the stub, through the severed fragment's start and end, and
 * back in -- so it is detected by walking successors, not by looking for a self-edge.
 *
 * <p>
 * <b>Mechanism.</b> {@code AttachNewExtremitiesToStubCommand} collects every StartPoint and
 * EndPoint whose id is newer than the command -- the severed ends left by deleting the selected
 * nodes -- and attaches each to the stub, EndPoints as in-paths and StartPoints as out-paths.
 * Deleting a fork/join block severs the path in more than one place, so more than one pair is
 * produced, and a pair whose two ends both attach to the same stub is exactly the extra loop.
 *
 * <p>
 * <b>Ruled out.</b> The class contains {@code removeTinyBranch()}, implemented but with its call
 * site commented out, which deletes a fragment whose start and end are both scheduled for
 * attachment. Enabling it changes nothing here: the result is still
 * {@code loopingOutPaths=1 in=2 out=2}, byte for byte. It also breaks no existing test, so it was
 * not disabled because it regressed anything covered. Its guard requires both ends to be in
 * {@code toAttach}, and at least one end of the offending pair evidently is not -- note that the
 * separate {@code alsoAttachThese} loop (fed by
 * {@code RefactorIntoStubCommand.findDisconnectedBranches()}) attaches without any such guard.
 *
 * <p>
 * Disabled so the suite stays green. Enable it when the attachment logic is fixed; the assertion
 * states the wanted outcome, not the current one. Tracked in #29.
 *
 * @author Claude
 */
public class RefactorIntoStubLoopTest {

    private JUCMNavTestFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new JUCMNavTestFixture();
        fixture.initjucmnav();

        // See RefactorIntoStubUndoTest: without this the refactor's constructor opens a modal
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
     * How many of the stub's out-paths lead back into the stub.
     *
     * A reachability walk, not a check for a single self-edge: the spurious path runs out of the
     * stub, through the severed fragment's start and end, and back in, so it is several hops long.
     */
    private int loopingOutPaths(Stub stub) {
        int loops = 0;
        for (Iterator it = stub.getSucc().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (leadsBackTo(nc.getTarget(), stub, new Vector<IURNNode>()))
                loops++;
        }
        return loops;
    }

    private boolean leadsBackTo(IURNNode from, Stub stub, Vector<IURNNode> seen) {
        if (from == null || seen.contains(from))
            return false;
        if (from == stub)
            return true;
        seen.add(from);
        for (Iterator it = from.getSucc().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (leadsBackTo(nc.getTarget(), stub, seen))
                return true;
        }
        return false;
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

    /** The fork's outgoing connection that is not the spare branch. */
    private NodeConnection mainBranchOut(OrFork fork, EndPoint spare) {
        for (Iterator it = fork.getSucc().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (nc.getTarget() != spare)
                return nc;
        }
        return (NodeConnection) fork.getSucc().get(0);
    }

    private Stub theStub(UCMmap map) {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (pn instanceof Stub)
                return (Stub) pn;
        }
        return null;
    }

    /**
     * start -> fork -> {branch A, branch B} -> join -> end, then refactor fork+join into a stub.
     */
    @Test
    @Ignore("reproduces the spurious stub self-loop; unfixed, see #29")
    public void refactoringAForkJoinBlockDoesNotLeaveASelfLoop() {
        UCMmap map = (UCMmap) fixture.map;

        // start -> empty -> end
        StartPoint start = (StartPoint) ModelCreationFactory.getNewObject(fixture.urnspec, StartPoint.class);
        Command cmd = new CreatePathCommand(map, start, 100, 100);
        assertTrue("CreatePathCommand must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        // insert an OR-fork on the first connection; DividePathCommand also runs an
        // AddBranchCommand, which spawns a second branch ending in a fresh EndPoint.
        Vector<EndPoint> endsBefore = endPoints(map);
        OrFork fork = (OrFork) ModelCreationFactory.getNewObject(fixture.urnspec, OrFork.class);
        cmd = new DividePathCommand(fork, (NodeConnection) map.getConnections().get(0), 200, 100);
        assertTrue("DividePathCommand(fork) must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        // Identify the spare branch by diffing the EndPoint set rather than assuming the shape
        // AddBranchCommand produces.
        Vector<EndPoint> spares = endPoints(map);
        spares.removeAll(endsBefore);
        assertEquals("the fork should have spawned exactly one spare branch", 1, spares.size()); //$NON-NLS-1$
        EndPoint spare = spares.get(0);

        // Insert the OR-join on the branch leaving the fork, attaching the spare branch to it in
        // the same command -- this is the constructor meant for recombining an existing branch.
        OrJoin join = (OrJoin) ModelCreationFactory.getNewObject(fixture.urnspec, OrJoin.class);
        NodeConnection afterFork = mainBranchOut(fork, spare);
        cmd = new DividePathCommand(join, afterFork, 400, 100, spare);
        assertTrue("DividePathCommand(join) must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        // refactor the fork/join block into a stub
        Vector<Object> selection = new Vector<Object>();
        selection.add(fork);
        selection.add(join);
        Command refactor = new RefactorIntoStubCommand(fixture.urnspec, selection);
        assertTrue("RefactorIntoStubCommand must execute", refactor.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(refactor);

        Stub stub = theStub(map);
        assertTrue("the refactor should have left a stub on the original map", stub != null); //$NON-NLS-1$

        // One assertion carrying all three numbers: an ordered set of assertEquals stops at the
        // first failure and hides the rest, which cost a build cycle while diagnosing this.
        assertEquals("refactoring a fork/join block should leave a plain in/out stub", //$NON-NLS-1$
                "loopingOutPaths=0 in=1 out=1", //$NON-NLS-1$
                "loopingOutPaths=" + loopingOutPaths(stub) + " in=" + stub.getPred().size() //$NON-NLS-1$ //$NON-NLS-2$
                        + " out=" + stub.getSucc().size()); //$NON-NLS-1$
    }
}
