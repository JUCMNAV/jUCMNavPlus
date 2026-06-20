package seg.jUCMNav.tests.figures;

import org.eclipse.draw2d.SWTGraphics;
import org.eclipse.draw2d.ScaledGraphics;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import junit.framework.TestCase;
import seg.jUCMNav.figures.ComponentRefFigure;
import urncore.ComponentKind;

/**
 * Regression test for issue #5: the UCM ActorRef "stickman" symbol is rendered
 * incorrectly in clipboard copy / image export at every zoom -- segments missing
 * and a stray line appearing, even though the on-screen rendering is correct.
 *
 * Root cause: the stickman (ComponentRefFigure.outlineShape, ComponentKind.ACTOR
 * branch) used to draw its segments with several drawPolyline() calls. Copy and
 * image export paint through draw2d's ScaledGraphics (any scale != 1.0), whose
 * zoomPointList() serves scaled coordinates from a single int[] cache keyed only
 * by array length -- so two equal-length polylines (the arms and a leg) collide
 * on the same buffer and one is corrupted. The fix draws each segment with
 * drawLine(), which scales endpoints directly with no shared cache.
 *
 * This test does NOT merely count ink (the original mistake): it samples the
 * five distinct stickman elements -- head, arms, body, left leg, right leg --
 * and asserts each one renders, through both the direct and the ScaledGraphics
 * paths. Verified to fail (right leg absent) before the drawLine fix.
 *
 * Figure bounds (PAD,PAD,FIG,FIG), default lineWidth 3 => stickman base
 * x = PAD+1+5, y = PAD+1-5. With PAD=5: x=11, y=1. Element coords (figure space):
 *   head   : oval(x+7,y+14,6,6)        -> ring around (21,18)
 *   arms   : (x+5,y+24)-(x+15,y+24)    -> horizontal y=25, x 16..26
 *   body   : (x+10,y+20)-(x+10,y+30)   -> vertical x=21, y 21..31
 *   leftLeg: (x+10,y+30)-(x+5,y+35)    -> (21,31)-(16,36)
 *   rightLeg:(x+10,y+30)-(x+15,y+35)   -> (21,31)-(26,36)
 *
 * @author Claude (QA modernization, issue #5)
 */
public class ActorStickmanExportTest extends TestCase {

    private static final int PAD = 5;
    private static final int FIG = 60;

    /** The stickman painted directly to an off-screen GC must show all five elements. */
    public void testStickmanShapeDirect() {
        assertAllElementsPresent("direct SWTGraphics", render(1.0, false)); //$NON-NLS-1$
    }

    /**
     * The decisive case: painted through ScaledGraphics -- the path every copy /
     * image export takes once scale != 1.0 (and the issue-#4 nudge forces even at
     * zoom 1.0) -- all five elements must still render, at every zoom. Before the
     * fix the right leg (a 2-point polyline colliding with the 2-point arms on the
     * ScaledGraphics length-keyed cache) was dropped here.
     */
    public void testStickmanShapeThroughScaledGraphics() {
        double[] zooms = { 1.001, 1.5, 2.0, 3.0 };
        for (int i = 0; i < zooms.length; i++) {
            assertAllElementsPresent("ScaledGraphics zoom " + zooms[i], render(zooms[i], true)); //$NON-NLS-1$
        }
    }

    private void assertAllElementsPresent(String path, Shot s) {
        assertTrue("[" + path + "] head missing", present(s, 21, 15) || present(s, 18, 18)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("[" + path + "] left arm missing", present(s, 17, 25)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("[" + path + "] right arm missing", present(s, 25, 25)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("[" + path + "] body missing", present(s, 21, 27) && present(s, 21, 29)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("[" + path + "] left leg missing", present(s, 18, 33)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("[" + path + "] right leg missing (issue #5)", present(s, 24, 34)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static class Shot {
        ImageData data;
        double scale;
    }

    private Shot render(double scale, boolean useScaled) {
        int imgW = (int) Math.ceil((FIG + 2 * PAD) * scale) + 4;
        Image image = new Image(Display.getDefault(), imgW, imgW);
        GC gc = new GC(image);
        // Same off-screen GC setup as CopyAction / ExportImage.
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
        gc.fillRectangle(0, 0, imgW, imgW);
        SWTGraphics swt = new SWTGraphics(gc);

        ComponentRefFigure figure = new ComponentRefFigure();
        figure.setKind(ComponentKind.ACTOR);
        figure.setColors("0,0,0", "255,255,255", true); //$NON-NLS-1$ //$NON-NLS-2$
        figure.setBounds(new Rectangle(PAD, PAD, FIG, FIG));

        ScaledGraphics scaled = null;
        if (useScaled) {
            scaled = new ScaledGraphics(swt);
            scaled.scale(scale);
            figure.paint(scaled);
        } else {
            figure.paint(swt);
        }

        Shot s = new Shot();
        s.data = image.getImageData();
        s.scale = scale;

        if (scaled != null)
            scaled.dispose();
        swt.dispose();
        gc.dispose();
        image.dispose();
        return s;
    }

    /** True if any dark pixel exists within a small radius of the scaled figure-space point. */
    private boolean present(Shot s, int figX, int figY) {
        int cx = (int) Math.round(figX * s.scale);
        int cy = (int) Math.round(figY * s.scale);
        int radius = Math.max(1, (int) Math.ceil(s.scale));
        PaletteData palette = s.data.palette;
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x < 0 || y < 0 || x >= s.data.width || y >= s.data.height)
                    continue;
                RGB rgb = palette.getRGB(s.data.getPixel(x, y));
                if (rgb.red < 128 && rgb.green < 128 && rgb.blue < 128)
                    return true;
            }
        }
        return false;
    }
}
