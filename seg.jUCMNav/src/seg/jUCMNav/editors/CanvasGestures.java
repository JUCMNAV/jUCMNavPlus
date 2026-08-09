package seg.jUCMNav.editors;

import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.EditPartViewer;
import org.eclipse.gef.editparts.ZoomManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.GestureEvent;
import org.eclipse.swt.events.GestureListener;
import org.eclipse.swt.widgets.Control;

/**
 * Pinch to zoom and two-finger pan on a diagram canvas.
 *
 * <p>
 * <b>Spike.</b> The SWT gesture APIs are present in the target platform, but how much actually
 * arrives depends on the window system: Cocoa has supported these for years, Win32 synthesises them
 * from {@code WM_GESTURE} for precision touchpads and touchscreens, and GTK may deliver nothing at
 * all. So this is strictly additive -- where no gesture arrives, nothing happens, and Ctrl+scroll
 * keeps working exactly as before. It is attached only if the platform admits to supporting it.
 *
 * <p>
 * None of this can be exercised by the test suite: gestures cannot be synthesised in the headless
 * harness, so there is no automated coverage and there cannot be. That is a real cost and the reason
 * this is a spike rather than a feature -- it has to be judged by hand, on hardware.
 *
 * <p>
 * Zoom is a view concern, not a model edit, so nothing here touches the command stack and nothing
 * here is undoable. Pinching a diagram must never appear in the undo history.
 *
 * @author Claude
 */
public class CanvasGestures implements GestureListener {

    /**
     * Below this the gesture is noise rather than intent.
     *
     * Trackpads report tiny magnifications continuously while two fingers rest on the surface, and
     * acting on all of them makes the diagram creep while nobody is doing anything.
     */
    private static final double MAGNIFY_DEADBAND = 0.01;

    private final ZoomManager zoomManager;
    private final FigureCanvas canvas;

    /**
     * The zoom when the current gesture began, or 0 between gestures.
     *
     * Anchoring on this rather than accumulating per-event deltas is deliberate: platforms disagree
     * about whether {@code magnification} is cumulative since the gesture started or a delta since
     * the last event, and multiplying deltas drifts. Multiplying a remembered start by a cumulative
     * factor is right under the first reading and merely less smooth under the second.
     */
    private double startZoom;

    private CanvasGestures(ZoomManager zoomManager, FigureCanvas canvas) {
        this.zoomManager = zoomManager;
        this.canvas = canvas;
    }

    /**
     * Attaches pinch-zoom and two-finger pan to a viewer, if the platform offers gestures.
     *
     * Safe to call unconditionally: it does nothing when the viewer has no canvas, no zoom manager,
     * or when the control rejects gesture handling.
     *
     * @param viewer
     *            the graphical viewer whose canvas should respond to gestures
     * @param zoomManager
     *            the zoom manager to drive -- for jUCMNav, the page's delegating one
     */
    public static void attach(EditPartViewer viewer, ZoomManager zoomManager) {
        if (viewer == null || zoomManager == null)
            return;

        Control control = viewer.getControl();
        if (!(control instanceof FigureCanvas) || control.isDisposed())
            return;

        FigureCanvas canvas = (FigureCanvas) control;
        try {
            canvas.addGestureListener(new CanvasGestures(zoomManager, canvas));
        } catch (Throwable unsupported) {
            // A window system without gesture support is not an error. The user keeps Ctrl+scroll,
            // the scrollbars, and the zoom combo; they simply do not get to pinch.
        }
    }

    public void gesture(GestureEvent event) {
        switch (event.detail) {
        case SWT.GESTURE_BEGIN:
            startZoom = zoomManager.getZoom();
            break;

        case SWT.GESTURE_MAGNIFY:
            magnify(event);
            break;

        case SWT.GESTURE_PAN:
            pan(event);
            break;

        case SWT.GESTURE_END:
            startZoom = 0;
            break;

        default:
            break; // rotate and swipe mean nothing on a UCM canvas
        }
    }

    /**
     * Pinch, anchored so the point under the fingers stays under the fingers.
     *
     * <p>
     * Without the anchoring this still zooms, but the diagram slides out from under the gesture and
     * it feels like dragging rather than scaling. The correction is the whole difference between
     * "works" and "feels right", and it is four lines.
     */
    private void magnify(GestureEvent event) {
        if (startZoom <= 0 || Math.abs(event.magnification - 1.0) < MAGNIFY_DEADBAND)
            return;

        double wanted = clamp(startZoom * event.magnification);
        double current = zoomManager.getZoom();
        if (wanted == current)
            return;

        // Where the fingers are, in diagram coordinates, before the zoom changes.
        Viewport viewport = canvas.getViewport();
        Point view = viewport.getViewLocation();
        double anchorX = (view.x + event.x) / current;
        double anchorY = (view.y + event.y) / current;

        zoomManager.setZoom(wanted);

        // Put that same diagram point back under the fingers afterwards.
        zoomManager.setViewLocation(new Point((int) Math.round(anchorX * wanted - event.x),
                (int) Math.round(anchorY * wanted - event.y)));
    }

    /** Two-finger pan: scroll the viewport, leaving the zoom alone. */
    private void pan(GestureEvent event) {
        if (event.xDirection == 0 && event.yDirection == 0)
            return;

        Point at = canvas.getViewport().getViewLocation();

        // The directions are how far the content should move, so the view moves the other way.
        canvas.scrollTo(at.x - event.xDirection, at.y - event.yDirection);
    }

    /** Keeps a pinch inside the zoom manager's own limits rather than fighting them afterwards. */
    private double clamp(double zoom) {
        return Math.max(zoomManager.getMinZoom(), Math.min(zoomManager.getMaxZoom(), zoom));
    }
}
