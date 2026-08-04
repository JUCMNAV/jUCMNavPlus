package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

import urncore.IURNContainerRef;
import urncore.IURNNode;

/**
 * Pushes overlapping component boxes apart, after the nodes have been placed freely.
 *
 * <p>
 * The layout constraint URN actually imposes is narrow: a container must not overlap a sibling, and
 * anything drawn inside a container must be bound to it. Neither of the two mechanisms tried before
 * expresses just that.
 *
 * <ul>
 * <li>A Graphviz <b>cluster</b> is a 2-D box, which is right, but it also packs its members into
 * adjacent ranks -- a constraint URN does not impose. A component acting at several points along a
 * path then forces the path to leave and re-enter it, which is the tangle.</li>
 * <li>A <b>swimlane band</b> satisfies the rules by collapsing the problem to one dimension, which
 * throws away most of the solution space and makes a BPMN lane out of a UCM component.</li>
 * </ul>
 *
 * <p>
 * So nodes are placed freely by topology -- which draws the clean flow -- and only then are the
 * boxes those nodes imply separated, by translating whole components until none overlap. Position
 * within a component is untouched, so the path keeps the shape Graphviz gave it; only the component
 * as a whole slides. Free nodes, belonging to no component, are pushed out of any box that would
 * otherwise appear to contain them.
 *
 * <p>
 * The push is along the axis of <i>least</i> overlap, which is the smallest correction that
 * resolves it and so disturbs the drawing least.
 *
 * @author Claude
 */
public class ComponentSeparation {

    /** Clear space left between two component boxes, and around a free node pushed out of one. */
    private static final int SEPARATION = 24;

    /** Overlap resolution is iterative because moving one box can push it into a third. */
    private static final int MAX_PASSES = 60;

    /**
     * Separates the component boxes implied by these positions.
     *
     * @param positions
     *            node -&gt; centre, modified in place
     * @param sizes
     *            node -&gt; how big it is drawn; a node absent from this map is treated as a point
     * @param margin
     *            the margin a component's rectangle is drawn with around its contents
     * @return the same map, for chaining
     */
    public static Map<IURNNode, Point> apply(Map<IURNNode, Point> positions, Map<IURNNode, Dimension> sizes, int margin) {
        if (positions == null || positions.isEmpty())
            return positions;

        Map<Object, List<IURNNode>> groups = new LinkedHashMap<Object, List<IURNNode>>();
        List<IURNNode> free = new ArrayList<IURNNode>();

        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Object owner = outermost(node);
            if (owner == null) {
                free.add(node);
                continue;
            }
            List<IURNNode> members = groups.get(owner);
            if (members == null)
                groups.put(owner, members = new ArrayList<IURNNode>());
            members.add(node);
        }

        List<List<IURNNode>> components = new ArrayList<List<IURNNode>>(groups.values());
        if (components.isEmpty())
            return positions;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;

            List<Rectangle> boxes = new ArrayList<Rectangle>();
            for (int i = 0; i < components.size(); i++)
                boxes.add(box(components.get(i), positions, sizes, margin));

            // Component against component.
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    Point push = separation(boxes.get(i), boxes.get(j));
                    if (push == null)
                        continue;

                    // Half each, so neither component is privileged and the drawing stays centred.
                    translate(components.get(i), positions, -push.x / 2, -push.y / 2);
                    translate(components.get(j), positions, push.x - push.x / 2, push.y - push.y / 2);
                    boxes.set(i, box(components.get(i), positions, sizes, margin));
                    boxes.set(j, box(components.get(j), positions, sizes, margin));
                    moved = true;
                }
            }

            // A node in no component is pushed out of one only when its containment would mean
            // something. For a responsibility or a path end it does: drawn inside a component, it
            // reads as performed by it. For a fork, a join, an empty point or a direction arrow it
            // does not -- those carry no binding worth the name, so they may lie inside a component
            // or outside it without changing what the map says.
            //
            // That distinction is most of the room the layout has. Treating every unbound node as
            // untouchable forced the path to dive away from every component it passed, which is
            // what made the drawing lurch; letting the shape-only nodes lie where they fall lets a
            // path run through a component instead of around it.
            for (int f = 0; f < free.size(); f++) {
                IURNNode node = free.get(f);
                if (!bindingIsMeaningful(node))
                    continue;
                Rectangle nodeBox = box(java.util.Collections.singletonList(node), positions, sizes, 0);

                for (int i = 0; i < boxes.size(); i++) {
                    Point push = separation(boxes.get(i), nodeBox);
                    if (push == null)
                        continue;

                    Point at = positions.get(node);
                    positions.put(node, new Point(at.x + push.x, at.y + push.y));
                    nodeBox = box(java.util.Collections.singletonList(node), positions, sizes, 0);
                    moved = true;
                }
            }

            if (!moved)
                return positions;
        }

        return positions;
    }

    /**
     * How far {@code b} must move to clear {@code a}, or null if they already do.
     *
     * Along the axis of least overlap: the smallest correction that resolves it, and so the one
     * that disturbs the layout least.
     */
    private static Point separation(Rectangle a, Rectangle b) {
        if (!a.intersects(b))
            return null;

        int right = a.right() - b.x + SEPARATION;
        int left = b.right() - a.x + SEPARATION;
        int down = a.bottom() - b.y + SEPARATION;
        int up = b.bottom() - a.y + SEPARATION;

        int dx = right < left ? right : -left;
        int dy = down < up ? down : -up;

        return Math.abs(dx) <= Math.abs(dy) ? new Point(dx, 0) : new Point(0, dy);
    }

    /** The rectangle a set of nodes implies, allowing for how big each one is drawn. */
    private static Rectangle box(List<IURNNode> nodes, Map<IURNNode, Point> positions, Map<IURNNode, Dimension> sizes, int margin) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;

        for (int i = 0; i < nodes.size(); i++) {
            Point at = positions.get(nodes.get(i));
            if (at == null)
                continue;

            // A node's extent matters, not just its centre. GRL intentional elements are drawn
            // around 150x85, so a box taken from centres alone is far too small and the actor ends
            // up not visually containing the elements bound to it -- which is a rule violation, and
            // was visible on every GRL sample.
            Dimension size = sizes == null ? null : sizes.get(nodes.get(i));
            int halfWidth = size == null ? 0 : size.width / 2;
            int halfHeight = size == null ? 0 : size.height / 2;

            left = Math.min(left, at.x - halfWidth);
            top = Math.min(top, at.y - halfHeight);
            right = Math.max(right, at.x + halfWidth);
            bottom = Math.max(bottom, at.y + halfHeight);
        }

        if (left == Integer.MAX_VALUE)
            return new Rectangle(0, 0, 0, 0);

        return new Rectangle(left - margin, top - margin, right - left + 2 * margin, bottom - top + 2 * margin);
    }

    private static void translate(List<IURNNode> nodes, Map<IURNNode, Point> positions, int dx, int dy) {
        for (int i = 0; i < nodes.size(); i++) {
            Point at = positions.get(nodes.get(i));
            if (at != null)
                positions.put(nodes.get(i), new Point(at.x + dx, at.y + dy));
        }
    }

    /**
     * Whether it matters which component this node is drawn inside.
     *
     * <p>
     * A responsibility is performed by a component, and a start or end point belongs to one, so
     * drawing them inside the wrong box says something false. A fork, a join, an empty point or a
     * direction arrow is pure shape -- it marks where a path branches or bends, not who does the
     * work -- so its position relative to a component boundary asserts nothing.
     */
    public static boolean bindingIsMeaningful(IURNNode node) {
        return !(node instanceof ucm.map.EmptyPoint || node instanceof ucm.map.OrFork || node instanceof ucm.map.AndFork
                || node instanceof ucm.map.OrJoin || node instanceof ucm.map.AndJoin || node instanceof ucm.map.DirectionArrow);
    }

    /** The outermost container holding this node, or null when it is in none. */
    private static Object outermost(IURNNode node) {
        IURNContainerRef ref = node.getContRef();
        if (ref == null)
            return null;

        while (ref.getParent() != null)
            ref = ref.getParent();
        return ref;
    }
}
