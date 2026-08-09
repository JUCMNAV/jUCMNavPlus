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
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Pinch to zoom and two-finger pan on a diagram canvas.
 *
 * <p>
 * <b>Spike.</b> The SWT gesture APIs are present in the target platform, but how much actually
 * arrives depends on the window system: Cocoa has supported these for years, Win32 synthesises them
 * from {@code WM_GESTURE} for precision touchpads and touchscreens, and GTK may deliver nothing at
 * all. So a pinch is also accepted as Ctrl+wheel, which is how a Windows Precision Touchpad
 * actually reports one -- that path needs no gesture support at all and covers every platform.
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

    /** Zoom change per Ctrl+wheel notch. 10% is what most drawing tools use. */
    private static final double WHEEL_STEP = 1.1;

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
        final CanvasGestures gestures = new CanvasGestures(zoomManager, canvas);

        try {
            canvas.addGestureListener(gestures);
        } catch (Throwable unsupported) {
            // A window system without gesture support is not an error; the wheel path below still
            // works, and so do the scrollbars and the zoom combo.
        }

        // Ctrl+wheel, which is what a pinch actually arrives as on a Windows Precision Touchpad.
        // Those report pinch to the application as a wheel event with Ctrl held rather than as a
        // WM_GESTURE, so GESTURE_MAGNIFY never fires and the gesture listener above sees nothing --
        // which is exactly the "panning works, zoom does nothing" symptom. Handling it here covers
        // the trackpad, the mouse wheel, and every platform, without depending on which of them
        // synthesises gestures.
        canvas.addListener(SWT.MouseWheel, new Listener() {
            public void handleEvent(Event event) {
                if ((event.stateMask & SWT.MOD1) == 0 || event.count == 0)
                    return;

                gestures.wheelZoom(event.count, event.x, event.y);
                event.doit = false; // consumed: do not also scroll the viewport
            }
        });
    }

    /**
     * One notch of Ctrl+wheel, anchored on the pointer.
     *
     * @param notches
     *            positive to zoom in, negative out
     */
    void wheelZoom(int notches, int x, int y) {
        double current = zoomManager.getZoom();
        double wanted = clamp(current * Math.pow(WHEEL_STEP, notches));
        if (wanted == current)
            return;

        zoomTo(wanted, current, x, y);
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

        double current = zoomManager.getZoom();
        double wanted = clamp(startZoom * event.magnification);
        if (wanted == current)
            return;

        zoomTo(wanted, current, event.x, event.y);
    }

    /**
     * Sets the zoom while keeping the diagram point under (x, y) under (x, y).
     *
     * Without this the drawing slides out from under the gesture and reads as dragging rather than
     * scaling. Shared by the pinch and the Ctrl+wheel paths so both feel the same.
     */
    private void zoomTo(double wanted, double current, int x, int y) {
        Viewport viewport = canvas.getViewport();
        Point view = viewport.getViewLocation();
        double anchorX = (view.x + x) / current;
        double anchorY = (view.y + y) / current;

        zoomManager.setZoom(wanted);
        zoomManager.setViewLocation(new Point((int) Math.round(anchorX * wanted - x),
                (int) Math.round(anchorY * wanted - y)));
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
