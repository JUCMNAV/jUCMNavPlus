package seg.jUCMNav.tests.figures;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.figures.router.UCMConnectionRouter;
import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.tests.commands.JUCMNavTestFixture;
import ucm.map.AndFork;
import ucm.map.EmptyPoint;
import ucm.map.NodeConnection;
import ucm.map.UCMmap;
import urn.URNspec;

/**
 * Regression test for issue #24 (legacy projetseg-update#930): routing a path
 * that loops through an AndFork blew the stack with a StackOverflowError.
 *
 * UCMConnectionRouter.refreshConnections walks forward out of every AndFork and
 * backward into every AndJoin, recursing through refreshConnections(NodeConnection)
 * -> refreshConnections(QFindSpline). That recursion had no visited set, so it was
 * only safe while the fork/join structure formed a DAG. Closing a loop through a
 * fork -- which is legal UCM, and what the reported repro does by linking an
 * AND-fork branch back to an upstream start point -- made the walk re-enter splines
 * it had already processed until the stack was exhausted.
 *
 * The minimal reproduction is a fork that is reachable from itself: the spline
 * leaving the fork ends up back at the same fork, so expanding the fork's
 * successors yields the very spline being expanded.
 *
 * Note ConnectionSplineFinder itself is NOT the culprit -- it is iterative and
 * already guards against cycles; it merely appears at the top of the reported
 * stack because it is the deepest call.
 *
 * @author Claude (QA modernization, issue #24)
 */
public class UCMConnectionRouterCycleTest {

    private JUCMNavTestFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new JUCMNavTestFixture();
        fixture.initjucmnav();
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null)
            fixture.cleanup();
        fixture = null;
    }

    /**
     * Build a fork whose outgoing branch loops straight back into it, then close
     * that loop while a router is listening. Before the fix this recursed until the
     * stack was exhausted; now it must simply return.
     */
    @Test
    public void routingAPathLoopingThroughAnAndForkTerminates() {
        URNspec urnspec = fixture.urnspec;
        UCMmap map = fixture.map;
        assertNotNull("fixture did not provide a map", map); //$NON-NLS-1$

        AndFork fork = (AndFork) ModelCreationFactory.getNewObject(urnspec, AndFork.class);
        EmptyPoint mid = (EmptyPoint) ModelCreationFactory.getNewObject(urnspec, EmptyPoint.class);
        map.getNodes().add(fork);
        map.getNodes().add(mid);

        // fork -> mid, already in place before the router starts listening.
        NodeConnection out = (NodeConnection) ModelCreationFactory.getNewObject(urnspec, NodeConnection.class);
        map.getConnections().add(out);
        out.setSource(fork);
        out.setTarget(mid);

        // mid -> fork, deliberately left open: setting its target below is what
        // closes the loop, and is the model-level equivalent of the reported
        // gesture (linking an AND-fork branch back to an upstream node).
        NodeConnection back = (NodeConnection) ModelCreationFactory.getNewObject(urnspec, NodeConnection.class);
        map.getConnections().add(back);
        back.setSource(mid);

        // The router registers itself as an EMF adapter on the map and its
        // connections, so the setTarget below reaches notifyChanged -> the
        // recursive refresh. An empty editpart registry is fine: drawConnection
        // returns early for connections it cannot find.
        UCMConnectionRouter router = new UCMConnectionRouter(new HashMap(), map);
        assertNotNull("router was not created", router); //$NON-NLS-1$

        // Closing the cycle. Before the fix this threw StackOverflowError, which is
        // an Error rather than an Exception -- so let it propagate and fail the test
        // rather than catching Throwable and reporting something vaguer.
        back.setTarget(fork);

        // Reaching here at all is the assertion; the router must also still have
        // the graph's connections recorded.
        assertTrue("router lost track of the map's connections", //$NON-NLS-1$
                router.getConnections().size() >= 2);
    }
}
