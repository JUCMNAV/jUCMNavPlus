package seg.jUCMNav.model.util;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Routes a path segment around the components it must not cross, by proposing bend points.
 *
 * <p>
 * A path node drawn inside a component reads as bound to it, so a path that merely passes through a
 * component it does not belong to is wrong -- and moving the component to clear the path is the
 * expensive fix, because it disturbs everything else placed around it. Adding a bend point is the
 * cheap one: the path goes round, and nothing else moves.
 *
 * <p>
 * Empty points exist precisely for this. They carry no semantics of their own, only shape, so
 * inserting one changes how a path is drawn and nothing about what it means.
 *
 * <p>
 * The detour is deliberately crude -- two waypoints hugging the nearer horizontal edge of the
 * obstacle -- because {@code ChainPlacement} redistributes a chain's interior along the route
 * afterwards, and an interpolating spline through evenly spaced points will smooth a rectangular
 * detour into a curve. Precision here would be spent and then discarded.
 *
 * <p>
 * Pure geometry: no model, no workbench.
 *
 * @author Claude
 */
public class PathDetour {

    /** How far outside an obstacle the detour passes. */
    private static final int CLEARANCE = 26;

    /** Resolution at which a segment is tested against an obstacle. */
    private static final int SAMPLES = 24;

    /**
     * Waypoints taking the segment from {@code a} to {@code b} clear of every obstacle it crosses.
     *
     * @return the waypoints in travel order, empty when the segment is already clear
     */
    public static PointList around(Point a, Point b, List<Rectangle> obstacles) {
        PointList waypoints = new PointList();
        if (a == null || b == null || obstacles == null)
            return waypoints;

        for (int i = 0; i < obstacles.size(); i++) {
            Rectangle obstacle = obstacles.get(i);
            if (!crosses(a, b, obstacle))
                continue;

            // Go over or under, whichever is the shorter departure from the straight line.
            int midY = (a.y + b.y) / 2;
            boolean over = Math.abs(midY - obstacle.y) <= Math.abs(midY - obstacle.bottom());
            int y = over ? obstacle.y - CLEARANCE : obstacle.bottom() + CLEARANCE;

            // Entered from the left or the right, so the two waypoints come out in travel order.
            boolean leftToRight = a.x <= b.x;
            int first = leftToRight ? obstacle.x - CLEARANCE : obstacle.right() + CLEARANCE;
            int second = leftToRight ? obstacle.right() + CLEARANCE : obstacle.x - CLEARANCE;

            waypoints.addPoint(new Point(first, y));
            waypoints.addPoint(new Point(second, y));
        }

        return waypoints;
    }

    /**
     * Whether the straight segment from a to b passes through the rectangle.
     *
     * Sampled rather than solved. The obstacles are tens of pixels across and the answer only has
     * to be right to within a bend point, so an exact line-rectangle intersection would be more
     * arithmetic for no better decision.
     */
    public static boolean crosses(Point a, Point b, Rectangle obstacle) {
        if (obstacle == null || obstacle.isEmpty())
            return false;

        for (int i = 0; i <= SAMPLES; i++) {
            int x = a.x + (b.x - a.x) * i / SAMPLES;
            int y = a.y + (b.y - a.y) * i / SAMPLES;
            if (obstacle.contains(x, y))
                return true;
        }
        return false;
    }

    /** The obstacles a segment actually crosses, for a caller that needs to know which. */
    public static List<Rectangle> crossedBy(Point a, Point b, List<Rectangle> obstacles) {
        List<Rectangle> hit = new ArrayList<Rectangle>();
        for (int i = 0; obstacles != null && i < obstacles.size(); i++)
            if (crosses(a, b, obstacles.get(i)))
                hit.add(obstacles.get(i));
        return hit;
    }
}
