package seg.jUCMNav.model.util;

import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;

/**
 * Where the nodes of a chain go, chosen so that the curve drawn through them is smooth.
 *
 * <p>
 * jUCMNav draws a path as an <b>interpolating</b> cubic spline: {@code BSpline} passes exactly
 * through every path node and derives its tangents from the neighbours. The consequence is that
 * path shape is a function of node <i>spacing and turn angle</i> -- not of topology. Nodes bunched
 * up and then far apart, or three nearly collinear followed by a sharp turn, produce the overshoot
 * that reads as a bulging, looping path.
 *
 * <p>
 * Neither of those is anything a layered graph layout reasons about, which is why asking Graphviz
 * for the whole drawing produced bad curves even when it produced positions at all. This places the
 * interior of a chain directly instead:
 *
 * <ol>
 * <li>take the route between the two junctions -- the polyline Graphviz found for the contracted
 * edge, which is where the chain has to go to avoid everything else;</li>
 * <li>smooth it, so the sequence the spline interpolates has no sharp interior corners of its own;</li>
 * <li>drop the chain's nodes onto it at <b>equal arc length</b>.</li>
 * </ol>
 *
 * Equal spacing is the whole trick. A cubic interpolating spline through evenly spaced points that
 * turn gradually cannot overshoot; the failure mode needs uneven spacing or a sudden change of
 * direction, and this construction admits neither.
 *
 * <p>
 * Pure geometry -- no model, no workbench -- so the placement can be tested directly.
 *
 * @author Claude
 */
public class ChainPlacement {

    /**
     * Rounds off corners by repeated midpoint refinement (Chaikin). Two passes takes a polyline
     * with hard turns to something with no corner sharper than its neighbours, which is what the
     * interpolating spline needs; more than that starts pulling the route away from where Graphviz
     * put it, and the route is avoiding other things.
     */
    private static final int SMOOTHING_PASSES = 2;

    /** Below this, a route is a straight line and refining it only adds numerical noise. */
    private static final double MIN_SEGMENT = 1e-6;

    /**
     * Positions for {@code count} nodes strung along {@code route}, excluding its endpoints.
     *
     * @param route
     *            the path between the two junctions, junction to junction. Two points means a
     *            straight line between them.
     * @param count
     *            how many interior nodes to place
     * @return exactly {@code count} points, evenly spaced by arc length, endpoints excluded
     */
    public static PointList distribute(PointList route, int count) {
        PointList result = new PointList();
        if (count <= 0 || route == null || route.size() < 2)
            return result;

        PointList smooth = smooth(route);
        double[] cumulative = arcLengths(smooth);
        double total = cumulative[cumulative.length - 1];

        if (total < MIN_SEGMENT) {
            // Degenerate route: everything would land on top of everything else, which is the one
            // arrangement guaranteed to look wrong. Fan out along x instead of stacking.
            Point at = smooth.getPoint(0);
            for (int i = 0; i < count; i++)
                result.addPoint(at.x + (i + 1) * 20, at.y);
            return result;
        }

        // count nodes, count+1 gaps: the junctions at either end are already placed, so the
        // interior sits strictly between them at 1/(n+1), 2/(n+1), ...
        for (int i = 1; i <= count; i++)
            result.addPoint(pointAt(smooth, cumulative, total * i / (count + 1.0)));

        return result;
    }

    /**
     * Chaikin corner cutting, with the endpoints pinned.
     *
     * The ends must not move: they are the junctions, which Graphviz placed and which the chain has
     * to actually reach.
     */
    public static PointList smooth(PointList route) {
        PointList current = copy(route);

        for (int pass = 0; pass < SMOOTHING_PASSES && current.size() > 2; pass++) {
            PointList next = new PointList();
            next.addPoint(current.getPoint(0));

            for (int i = 0; i < current.size() - 1; i++) {
                Point a = current.getPoint(i);
                Point b = current.getPoint(i + 1);
                next.addPoint(new Point((int) Math.round(0.75 * a.x + 0.25 * b.x), (int) Math.round(0.75 * a.y + 0.25 * b.y)));
                next.addPoint(new Point((int) Math.round(0.25 * a.x + 0.75 * b.x), (int) Math.round(0.25 * a.y + 0.75 * b.y)));
            }

            next.addPoint(current.getPoint(current.size() - 1));
            current = next;
        }

        return current;
    }

    /** Cumulative distance along the polyline, starting at 0. */
    private static double[] arcLengths(PointList pts) {
        double[] cumulative = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            Point a = pts.getPoint(i - 1);
            Point b = pts.getPoint(i);
            cumulative[i] = cumulative[i - 1] + Math.hypot(b.x - a.x, b.y - a.y);
        }
        return cumulative;
    }

    /** The point at a given distance along the polyline, interpolating within a segment. */
    private static Point pointAt(PointList pts, double[] cumulative, double distance) {
        for (int i = 1; i < cumulative.length; i++) {
            if (cumulative[i] < distance)
                continue;

            double span = cumulative[i] - cumulative[i - 1];
            double t = span < MIN_SEGMENT ? 0 : (distance - cumulative[i - 1]) / span;
            Point a = pts.getPoint(i - 1);
            Point b = pts.getPoint(i);
            return new Point((int) Math.round(a.x + t * (b.x - a.x)), (int) Math.round(a.y + t * (b.y - a.y)));
        }
        return pts.getPoint(pts.size() - 1);
    }

    private static PointList copy(PointList source) {
        PointList copy = new PointList();
        for (int i = 0; i < source.size(); i++)
            copy.addPoint(source.getPoint(i));
        return copy;
    }

    /**
     * The sharpest turn in a sequence, in degrees, or 0 for fewer than three points.
     *
     * The quantity that decides whether an interpolating spline overshoots, so it is what a test
     * asserts on rather than eyeballing the curve.
     */
    public static double sharpestTurn(PointList pts) {
        double worst = 0;
        for (int i = 1; i < pts.size() - 1; i++) {
            Point a = pts.getPoint(i - 1), b = pts.getPoint(i), c = pts.getPoint(i + 1);
            double abx = b.x - a.x, aby = b.y - a.y;
            double bcx = c.x - b.x, bcy = c.y - b.y;
            double la = Math.hypot(abx, aby), lb = Math.hypot(bcx, bcy);
            if (la < MIN_SEGMENT || lb < MIN_SEGMENT)
                continue;

            double cos = (abx * bcx + aby * bcy) / (la * lb);
            worst = Math.max(worst, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))));
        }
        return worst;
    }

    /** The ratio of longest to shortest gap in a sequence; 1.0 is perfectly even. */
    public static double spacingRatio(PointList pts) {
        if (pts.size() < 2)
            return 1.0;

        double min = Double.MAX_VALUE, max = 0;
        for (int i = 1; i < pts.size(); i++) {
            Point a = pts.getPoint(i - 1), b = pts.getPoint(i);
            double d = Math.hypot(b.x - a.x, b.y - a.y);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        return min < MIN_SEGMENT ? Double.MAX_VALUE : max / min;
    }
}
