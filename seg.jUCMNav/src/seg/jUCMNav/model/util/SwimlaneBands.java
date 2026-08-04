package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.draw2d.geometry.Point;

import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * Puts every node in the band of the component that performs it.
 *
 * <p>
 * Position carries meaning in URN, and jUCMNav states it as OCL: a component must not overlap
 * another component, and anything visually inside a component must be bound to it. A layered graph
 * layout knows none of that -- it places nodes by topology, so a component's rectangle, derived
 * from where its nodes land, ends up spanning and swallowing its neighbours. On the reporter's
 * issue-tracker map that turned one pre-existing violation into twelve.
 *
 * <p>
 * Graphviz clusters are not the answer either, and were tried: a cluster is a tight box its members
 * are packed into, whereas a UCM component is a <b>band</b> -- the Triage Team spans that whole map
 * because the team acts at several points along the path. Boxed, the path has to leave and re-enter
 * at each of them, which is a tangle.
 *
 * <p>
 * So the horizontal position stays exactly as Graphviz chose it -- that is the topology, and it is
 * what Graphviz is good at -- and only the vertical position is reassigned: each component gets a
 * horizontal band of its own, disjoint from every other, and each node is moved into the band of
 * its own component. Nodes belonging to no component get a band too, so they cannot fall inside
 * one. Both rule families then hold <i>by construction</i>: bands cannot overlap, and a node cannot
 * land in a band that does not own it.
 *
 * <p>
 * This is also what the hand-drawn models look like, which is the standard the layout is being held
 * to.
 *
 * <p>
 * Pure geometry over a position map -- no model mutation, no workbench -- so it can be tested
 * directly.
 *
 * @author Claude
 */
public class SwimlaneBands {

    /** Vertical room for one band's contents before the gap is added. */
    private static final int MIN_BAND_HEIGHT = 70;

    /**
     * Clear space between one band and the next.
     *
     * Must exceed twice the margin a component's rectangle is drawn with, or two adjacent
     * components would still overlap once their boxes are inflated around their nodes.
     */
    private static final int BAND_GAP = 90;

    /** Where the topmost band starts. */
    private static final int TOP = 60;

    /**
     * Reassigns the vertical position of every node so components become disjoint bands.
     *
     * @param positions
     *            node -&gt; position, as produced by the layout. Modified in place.
     * @return the same map, for chaining
     */
    public static Map<IURNNode, Point> apply(Map<IURNNode, Point> positions) {
        if (positions == null || positions.isEmpty())
            return positions;

        // Group by the outermost component, so a nested component travels inside its parent's band
        // rather than claiming one of its own and cutting the parent in half.
        Map<Object, List<IURNNode>> bands = new LinkedHashMap<Object, List<IURNNode>>();
        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Object key = outermost(node);
            List<IURNNode> members = bands.get(key);
            if (members == null)
                bands.put(key, members = new ArrayList<IURNNode>());
            members.add(node);
        }

        // Keep the vertical order Graphviz chose. It reflects the topology -- parallel branches
        // came out above and below one another -- and reordering bands arbitrarily would undo that
        // for no gain.
        final Map<Object, Double> meanY = new HashMap<Object, Double>();
        for (Iterator<Object> it = bands.keySet().iterator(); it.hasNext();) {
            Object key = it.next();
            List<IURNNode> members = bands.get(key);
            double sum = 0;
            for (int i = 0; i < members.size(); i++)
                sum += positions.get(members.get(i)).y;
            meanY.put(key, Double.valueOf(sum / members.size()));
        }

        List<Object> order = new ArrayList<Object>(bands.keySet());
        Collections.sort(order, new Comparator<Object>() {
            public int compare(Object a, Object b) {
                return Double.compare(meanY.get(a).doubleValue(), meanY.get(b).doubleValue());
            }
        });

        int top = TOP;
        for (Iterator<Object> it = order.iterator(); it.hasNext();) {
            List<IURNNode> members = bands.get(it.next());
            top = placeBand(positions, members, top);
        }

        return positions;
    }

    /**
     * Squeezes one band's nodes into a horizontal strip, preserving their relative order.
     *
     * @return where the next band may start
     */
    private static int placeBand(Map<IURNNode, Point> positions, List<IURNNode> members, int top) {
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int i = 0; i < members.size(); i++) {
            int y = positions.get(members.get(i)).y;
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
        }

        int span = highest - lowest;
        int height = Math.max(MIN_BAND_HEIGHT, span);

        for (int i = 0; i < members.size(); i++) {
            IURNNode node = members.get(i);
            Point at = positions.get(node);

            // Relative position within the band is kept, so nodes that Graphviz put side by side --
            // the two branches of a fork, say -- stay side by side instead of collapsing onto one
            // line.
            int offset = span == 0 ? height / 2 : (at.y - lowest) * height / span;
            positions.put(node, new Point(at.x, top + offset));
        }

        return top + height + BAND_GAP;
    }

    /** The outermost component holding this node, or null when it is in none. */
    private static Object outermost(IURNNode node) {
        IURNContainerRef ref = node.getContRef();
        if (ref == null)
            return null;

        while (ref.getParent() != null)
            ref = ref.getParent();
        return ref;
    }
}
