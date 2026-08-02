package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
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
import ucm.map.Stub;
import ucm.map.UCMmap;
import urncore.IURNNode;

/**
 * Refactor into Stub should produce a stub whose in/out path counts equal the boundary of the
 * selection: X connections entering the selection from outside, Y leaving it.
 *
 * <p>
 * The boundary is computed from the model before the refactor rather than hard-coded, so the
 * assertion states the rule rather than one example of it and holds for any selection shape.
 *
 * <p>
 * <b>This currently passes.</b> It is a regression guard for the property, not a reproduction of
 * a defect. It is worth reading the history, because an earlier version of this file claimed to
 * reproduce the spurious-extra-path report and did not:
 *
 * <ul>
 * <li>it selected only the fork and the join, leaving the branch contents between them
 * unselected, and asserted 1 in / 1 out. That expectation is wrong for a non-contiguous
 * selection, whose boundary is larger -- every connection from the fork into an unselected
 * branch is itself an exit. The observed "in=2 out=2" was the command being plausibly correct
 * and the assertion being wrong;</li>
 * <li>with the whole block selected and the boundary computed, the counts match, both with bare
 * fork-&gt;join branches and with a responsibility on each branch.</li>
 * </ul>
 *
 * So the reported symptom is real (see #29) but is not triggered by this shape, and reproducing
 * it needs the reporter's actual model.
 *
 * <p>
 * <b>The design criticism stands independently.</b> The command never computes a boundary. It
 * creates a throwaway {@code start -> empty -> end} path and turns the empty into the stub,
 * giving an arbitrary 1-in/1-out scaffold; deletes the selected nodes, which incidentally spawns
 * start and end points wherever a path was severed; then sweeps the map for "every start/end
 * point newer than me" and attaches whatever it finds. That is why
 * {@code RefactorIntoStubBindingsCommand} has to re-derive stub-to-plugin bindings by matching
 * names afterwards -- a boundary-based construction would know them. This test encodes the
 * property such a rewrite should preserve.
 *
 * @author Claude
 */
public class RefactorIntoStubLoopTest {

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

    /** Connections entering the selection from outside it. */
    private int inBoundary(Set<PathNode> selection, UCMmap map) {
        int count = 0;
        for (Iterator it = map.getConnections().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (selection.contains(nc.getTarget()) && !selection.contains(nc.getSource()))
                count++;
        }
        return count;
    }

    /** Connections leaving the selection. */
    private int outBoundary(Set<PathNode> selection, UCMmap map) {
        int count = 0;
        for (Iterator it = map.getConnections().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            if (selection.contains(nc.getSource()) && !selection.contains(nc.getTarget()))
                count++;
        }
        return count;
    }

    /** Every node lying on some path from {@code from} to {@code to}, inclusive. */
    private Set<PathNode> block(PathNode from, PathNode to) {
        Set<PathNode> forward = new HashSet<PathNode>();
        collectForward(from, forward);

        Set<PathNode> selection = new HashSet<PathNode>();
        selection.add(from);
        selection.add(to);
        for (Iterator<PathNode> it = forward.iterator(); it.hasNext();) {
            PathNode candidate = it.next();
            if (selection.contains(candidate))
                continue;
            Set<PathNode> onward = new HashSet<PathNode>();
            collectForward(candidate, onward);
            if (onward.contains(to))
                selection.add(candidate);
        }
        return selection;
    }

    private void collectForward(IURNNode from, Set<PathNode> seen) {
        for (Iterator it = from.getSucc().iterator(); it.hasNext();) {
            NodeConnection nc = (NodeConnection) it.next();
            IURNNode target = nc.getTarget();
            if (target instanceof PathNode && seen.add((PathNode) target))
                collectForward(target, seen);
        }
    }

    private Stub theStub(UCMmap map) {
        for (Iterator it = map.getNodes().iterator(); it.hasNext();) {
            PathNode pn = (PathNode) it.next();
            if (pn instanceof Stub)
                return (Stub) pn;
        }
        return null;
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

    /**
     * start -&gt; fork -&gt; {A, B} -&gt; join -&gt; end, selecting the whole fork/join block.
     */
    @Test
    public void stubPathCountsMatchTheSelectionBoundary() {
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
        assertTrue("the fork should have a branch to insert the join on", afterFork != null); //$NON-NLS-1$
        cmd = new DividePathCommand(join, afterFork, 400, 100, spare);
        assertTrue("DividePathCommand(join) must execute", cmd.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(cmd);

        // Put a responsibility on each fork->join branch. Without this both branches are bare
        // fork->join edges, so "the branches and their content" from the report is not actually
        // represented and the block collapses to {fork, join}.
        // Snapshot first: splitting a connection mutates fork.getSucc() as we go. No assumption
        // about how many branches there are or where they lead -- whatever leaves the fork gets
        // a responsibility on it, so "the branches and their content" is genuinely represented.
        Vector<NodeConnection> branches = new Vector<NodeConnection>();
        for (Iterator it = fork.getSucc().iterator(); it.hasNext();)
            branches.add((NodeConnection) it.next());
        assertTrue("the fork should have at least one outgoing branch", branches.size() >= 1); //$NON-NLS-1$

        for (int i = 0; i < branches.size(); i++) {
            RespRef resp = (RespRef) ModelCreationFactory.getNewObject(fixture.urnspec, RespRef.class);
            Command split = new SplitLinkCommand(map, resp, branches.get(i), 300, 60 + (80 * i));
            assertTrue("SplitLinkCommand must execute", split.canExecute()); //$NON-NLS-1$
            fixture.cs.execute(split);
        }

        // The whole block: fork, join, and everything on a path between them.
        Set<PathNode> selection = block(fork, join);
        assertTrue("the block must contain at least the fork and the join", selection.size() >= 2); //$NON-NLS-1$

        int expectedIn = inBoundary(selection, map);
        int expectedOut = outBoundary(selection, map);

        Vector<Object> selectionArg = new Vector<Object>();
        selectionArg.addAll(selection);
        Command refactor = new RefactorIntoStubCommand(fixture.urnspec, selectionArg);
        assertTrue("RefactorIntoStubCommand must execute", refactor.canExecute()); //$NON-NLS-1$
        fixture.cs.execute(refactor);

        Stub stub = theStub(map);
        assertTrue("the refactor should have left a stub on the original map", stub != null); //$NON-NLS-1$

        // One assertion carrying both numbers: sequential assertEquals hide each other's values.
        assertEquals("the stub's path counts should equal the boundary of the selection", //$NON-NLS-1$
                "in=" + expectedIn + " out=" + expectedOut, //$NON-NLS-1$ //$NON-NLS-2$
                "in=" + stub.getPred().size() + " out=" + stub.getSucc().size()); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
