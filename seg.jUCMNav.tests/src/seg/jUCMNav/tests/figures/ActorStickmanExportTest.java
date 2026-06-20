package seg.jUCMNav.tests.figures;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.draw2d.SWTGraphics;
import org.eclipse.draw2d.ScaledGraphics;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import junit.framework.TestCase;
import seg.jUCMNav.actions.cutcopypaste.CopyAction;
import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.figures.ComponentRefFigure;
import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintBoundContainerRefCompoundCommand;
import seg.jUCMNav.model.commands.create.AddContainerRefCommand;
import ucm.map.ComponentRef;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.Component;
import urncore.ComponentKind;

/**
 * Reproduction / regression test for issue #5: the UCM ActorRef "stickman"
 * symbol is missing from clipboard copy / image export at every zoom.
 *
 * The stickman is painted by {@link ComponentRefFigure#outlineShape} in the
 * {@code ComponentKind.ACTOR} branch (drawPolyline / drawOval), right after the
 * component frame is drawn with drawRectangle.
 *
 * Three layers of coverage, cheapest first:
 *  1. the figure painted directly through the off-screen GC config that
 *     copy/export uses (advanced rendering + antialias);
 *  2. the figure painted through a ScaledGraphics (the wrap the scalable pane
 *     inserts for any scale != 1.0 -- the path every export takes);
 *  3. the real CopyAction.buildScreenshot path on a live editor containing an
 *     actual ACTOR component, compared against the same component rendered as a
 *     non-actor TEAM, so the stickman's extra ink is isolated from the frame and
 *     label.
 *
 * @author Claude (QA modernization, issue #5)
 */
public class ActorStickmanExportTest extends TestCase {

    private static final int FIG = 80; // component side length
    private static final int PAD = 5; // image padding around the figure

    private UCMNavMultiPageEditor editor;

    protected void tearDown() throws Exception {
        if (editor != null) {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            page.closeEditor(editor, false);
            editor = null;
        }
        super.tearDown();
    }

    /**
     * Paint an ACTOR ComponentRefFigure directly to an off-screen image the way
     * the copy/export code does, then assert the stickman glyph produced visible
     * pixels in the interior (away from the frame border).
     */
    public void testStickmanRendersInOffscreenExport() {
        ComponentRefFigure figure = new ComponentRefFigure();
        figure.setKind(ComponentKind.ACTOR);
        figure.setColors("0,0,0", "255,255,255", true); //$NON-NLS-1$ //$NON-NLS-2$
        figure.setBounds(new Rectangle(PAD, PAD, FIG, FIG));

        int imgW = FIG + 2 * PAD;
        int imgH = FIG + 2 * PAD;

        Image image = new Image(Display.getDefault(), imgW, imgH);
        GC gc = null;
        SWTGraphics graphics = null;
        ImageData data;
        try {
            gc = new GC(image);
            gc.setAdvanced(true);
            gc.setAntialias(SWT.ON);
            gc.setTextAntialias(SWT.ON);
            gc.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, imgW, imgH);

            graphics = new SWTGraphics(gc);
            figure.paint(graphics);
            data = image.getImageData();
        } finally {
            if (graphics != null)
                graphics.dispose();
            if (gc != null)
                gc.dispose();
            image.dispose();
        }

        int interiorDark = countDarkPixels(data, PAD + 8, PAD + 6, PAD + 24, PAD + 34);
        int frameDark = countDarkPixels(data, PAD - 1, PAD - 1, PAD + 1, PAD + FIG + 1);

        assertTrue("Frame border did not render at all -- test setup problem (frameDark=" + frameDark + ")", //$NON-NLS-1$
                frameDark > 0);
        assertTrue("Actor stickman is missing from the off-screen export: no ink found in the interior " //$NON-NLS-1$
                + "stickman region (interiorDark=" + interiorDark + "). Issue #5.", //$NON-NLS-1$ //$NON-NLS-2$
                interiorDark > 0);
    }

    /**
     * Same, but through a {@link ScaledGraphics} -- the wrapper the scalable pane
     * inserts for any scale != 1.0, which is the path every copy/export takes.
     */
    public void testStickmanRendersThroughScaledGraphics() {
        double[] zooms = { 1.001, 0.5, 2.0 };
        for (int i = 0; i < zooms.length; i++) {
            double zoom = zooms[i];
            int imgW = (int) Math.ceil((FIG + 2 * PAD) * zoom) + 4;
            int imgH = imgW;

            Image image = new Image(Display.getDefault(), imgW, imgH);
            GC gc = null;
            SWTGraphics swt = null;
            ScaledGraphics scaled = null;
            ImageData data;
            try {
                gc = new GC(image);
                gc.setAdvanced(true);
                gc.setAntialias(SWT.ON);
                gc.setTextAntialias(SWT.ON);
                gc.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, imgW, imgH);

                swt = new SWTGraphics(gc);
                scaled = new ScaledGraphics(swt);
                scaled.scale(zoom);

                ComponentRefFigure figure = new ComponentRefFigure();
                figure.setKind(ComponentKind.ACTOR);
                figure.setColors("0,0,0", "255,255,255", true); //$NON-NLS-1$ //$NON-NLS-2$
                figure.setBounds(new Rectangle(PAD, PAD, FIG, FIG));
                figure.paint(scaled);

                data = image.getImageData();
            } finally {
                if (scaled != null)
                    scaled.dispose();
                if (swt != null)
                    swt.dispose();
                if (gc != null)
                    gc.dispose();
                image.dispose();
            }

            int x0 = (int) ((PAD + 8) * zoom), y0 = (int) ((PAD + 6) * zoom);
            int x1 = (int) Math.ceil((PAD + 24) * zoom), y1 = (int) Math.ceil((PAD + 34) * zoom);
            int interiorDark = countDarkPixels(data, x0, y0, x1, y1);

            assertTrue("Actor stickman missing through ScaledGraphics at zoom " + zoom //$NON-NLS-1$
                    + ": no ink in interior stickman region (interiorDark=" + interiorDark + "). Issue #5.", //$NON-NLS-1$ //$NON-NLS-2$
                    interiorDark > 0);
        }
    }

    /**
     * The decisive test: run the real {@link CopyAction#buildScreenshot} path on a
     * live editor holding an actual ACTOR ComponentRef, and compare interior ink
     * against the very same component rendered as a non-actor TEAM. The frame and
     * label are identical in both; only the actor adds the stickman glyph, so a
     * materially higher interior-ink count for the actor proves the stickman
     * survived copy/export. This exercises the production pane composition + the
     * issue-#4 ZoomManager nudge end to end.
     */
    public void testStickmanSurvivesRealCopyScreenshot() throws Exception {
        int actorInk = renderComponentAndCountInteriorInk(ComponentKind.ACTOR_LITERAL);
        int teamInk = renderComponentAndCountInteriorInk(ComponentKind.TEAM_LITERAL);

        // The stickman is ~4 short strokes + a small oval; it adds well over a
        // handful of dark pixels. Require a clear margin over the actor-free TEAM
        // baseline (which captures any frame/label ink that leaks into the scan).
        assertTrue("Real copy/export screenshot is missing the actor stickman: actor interior ink (" //$NON-NLS-1$
                + actorInk + ") was not materially greater than the non-actor TEAM baseline (" + teamInk //$NON-NLS-1$
                + "). Issue #5.", actorInk > teamInk + 10); //$NON-NLS-1$
    }

    /**
     * Opens a fresh editor, drops a single 120x120 component of the given kind at a
     * known location, runs the real CopyAction.buildScreenshot, and counts dark
     * pixels in the interior region where the stickman would be (top-left), away
     * from the frame border.
     */
    private int renderComponentAndCountInteriorInk(ComponentKind kind) throws Exception {
        openFreshEditor();

        URNspec urnspec = editor.getModel();
        UCMmap map = (UCMmap) urnspec.getUrndef().getSpecDiagrams().get(0);
        CommandStack cs = editor.getDelegatingCommandStack();

        ComponentRef compRef = (ComponentRef) ModelCreationFactory.getNewObject(urnspec, ComponentRef.class);
        // blank the name so the label contributes no ink to the interior scan
        if (compRef.getContDef() instanceof Component) {
            Component def = (Component) compRef.getContDef();
            def.setName(""); //$NON-NLS-1$
            def.setKind(kind);
        }

        Command add = new AddContainerRefCommand(map, compRef);
        assertTrue("Can't add component ref", add.canExecute()); //$NON-NLS-1$
        cs.execute(add);
        Command bounds = new SetConstraintBoundContainerRefCompoundCommand(compRef, 40, 40, 120, 120);
        assertTrue("Can't set component bounds", bounds.canExecute()); //$NON-NLS-1$
        cs.execute(bounds);

        // let pending layout/refresh run so the pane sizes itself
        flushUiEvents();

        ImageData shot = CopyAction.buildScreenshot(editor);
        assertNotNull("buildScreenshot returned null", shot); //$NON-NLS-1$
        assertTrue("buildScreenshot produced an empty image (" + shot.width + "x" + shot.height //$NON-NLS-1$ //$NON-NLS-2$
                + ") -- editor pane was not laid out headlessly", shot.width > 20 && shot.height > 20); //$NON-NLS-1$

        // After cropping, the component frame hugs the image border. The stickman
        // sits just inside the top-left corner. Scan a top-left interior box that
        // excludes the outermost border ring (where the frame ink lives).
        int ring = 6;
        int interiorDark = countDarkPixels(shot, ring, ring, Math.min(shot.width - 1, 40),
                Math.min(shot.height - 1, 48));

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        page.closeEditor(editor, false);
        editor = null;

        return interiorDark;
    }

    private void openFreshEditor() throws Exception {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject("jUCMNav-tests"); //$NON-NLS-1$
        if (!project.exists())
            project.create(null);
        if (!project.isOpen())
            project.open(null);

        IFile file = project.getFile("jUCMNav-stickman-test.jucm"); //$NON-NLS-1$
        if (file.exists())
            file.delete(true, false, null);
        file.create(new ByteArrayInputStream("".getBytes()), false, null); //$NON-NLS-1$

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(file.getName());
        editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(file), desc.getId());
    }

    private void flushUiEvents() {
        Display display = Display.getDefault();
        for (int i = 0; i < 50 && display.readAndDispatch(); i++) {
            // drain
        }
    }

    /** Count pixels in the inclusive box [x0,x1]x[y0,y1] that are visibly dark (the black glyph ink). */
    private int countDarkPixels(ImageData data, int x0, int y0, int x1, int y1) {
        PaletteData palette = data.palette;
        int count = 0;
        for (int y = Math.max(0, y0); y <= Math.min(data.height - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(data.width - 1, x1); x++) {
                int pixel = data.getPixel(x, y);
                RGB rgb = palette.getRGB(pixel);
                if (rgb.red < 128 && rgb.green < 128 && rgb.blue < 128)
                    count++;
            }
        }
        return count;
    }
}
