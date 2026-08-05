package seg.jUCMNav.tests.commands;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.draw2d.LayeredPane;
import org.eclipse.draw2d.ScalableFreeformLayeredPane;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.editors.UrnEditor;
import seg.jUCMNav.editparts.URNRootEditPart;
import seg.jUCMNav.importexport.ExportContractedDOT;
import seg.jUCMNav.importexport.ExportImagePNG;
import seg.jUCMNav.importexport.PlainLayout;
import seg.jUCMNav.model.util.UcmPathDecomposition;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import seg.jUCMNav.views.wizards.AutoLayoutWizard;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;

/**
 * Renders a laid-out diagram to PNG so the result can actually be looked at.
 *
 * <p>
 * Every other auto-layout test asserts geometry -- spacing ratios, turn angles, that every node got
 * a position. Those are good proxies and they caught real defects, but none of them can tell you
 * that a drawing is <i>ugly</i>. A picture can.
 *
 * <p>
 * No separate command-line jUCMNav is needed for this: the test fragment already runs a real
 * workbench headlessly, and {@code ExportImagePNG} is the same exporter File &gt; Export uses, so
 * what lands on disk is exactly what the editor would draw. The images go to
 * {@code seg.jUCMNav.tests/target/layout-renders/} and the test asserts only that they were
 * produced and are not blank -- judging whether they look right is a human's job, or an AI's, but
 * either way it needs the file to exist.
 *
 * @author Claude
 */
public class AutoLayoutRenderTest {

    /** Where the renders land, relative to the tests module. */
    private static final String OUTPUT_DIR = "target/layout-renders"; //$NON-NLS-1$

    private UCMNavMultiPageEditor editor;
    private URNspec urn;
    private IProject project;

    @Before
    public void setUp() throws Exception {
        AutoLayoutPreferences.createPreferences();
        // The preference store is shared across the whole suite, so a test that changes the
        // orientation would silently change every later one. Reset it here rather than trusting
        // each test to clean up -- one that forgot cost an afternoon of confusing renders.
        AutoLayoutPreferences.getPreferenceStore().setToDefault(AutoLayoutPreferences.PREF_ORIENTATION);

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        project = root.getProject("jUCMNav-tests"); //$NON-NLS-1$
        if (!project.exists())
            project.create(null);
        if (!project.isOpen())
            project.open(null);
    }

    @After
    public void tearDown() {
        if (editor != null)
            editor.closeEditor(false);
        editor = null;
        urn = null;
    }

    private void open(String sample) throws Exception {
        IFile file = project.getFile(sample);
        if (file.exists())
            file.delete(true, false, null);
        file.create(AutoLayoutRenderTest.class.getResourceAsStream(sample), false, null);

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(file.getName());
        editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(file), desc.getId());
        urn = editor.getModel();
    }

    /**
     * Writes what the editor would draw for the current page.
     *
     * @return the file written, so a failure can name it
     */
    private File render(String name) throws Exception {
        UrnEditor page = (UrnEditor) editor.getCurrentPage();
        LayeredPane layers = ((URNRootEditPart) page.getGraphicalViewer().getRootEditPart()).getScaledLayers();

        File dir = new File(OUTPUT_DIR);
        dir.mkdirs();
        File out = new File(dir, name + ".png"); //$NON-NLS-1$

        FileOutputStream fos = new FileOutputStream(out);
        try {
            new ExportImagePNG().export((ScalableFreeformLayeredPane) layers, fos);
        } finally {
            fos.close();
        }

        System.out.println("[layout render] " + out.getAbsolutePath() + " (" + out.length() + " bytes)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return out;
    }

    /** Applies the real auto-layout pipeline to the map currently shown. */
    private void layoutCurrentPage() throws Exception {
        layout(((UrnEditor) editor.getCurrentPage()).getModel());
    }

    /**
     * Lays out the diagram given, rather than whichever page the editor considers current.
     *
     * Those are not always the same: setActivePage does not reliably switch in the headless
     * harness, so the demo sweep was laying out one map while believing it was laying out another.
     * That is how an empty map (SPS "VM", zero nodes) came to look like auto-layout failing on
     * SimpleConnection, which has sixteen.
     */
    private void layout(IURNDiagram diagram) throws Exception {
        UrnEditor page = (UrnEditor) editor.getCurrentPage();

        AutoLayoutWizard wizard = new AutoLayoutWizard(page, diagram);
        String dot = AutoLayoutPreferences.locateDot();
        org.junit.Assume.assumeTrue("Graphviz not installed; nothing to render", dot != null); //$NON-NLS-1$

        // Exactly what the wizard does by default -- layered swim lanes, no Graphviz. Rendering
        // anything else would render a code path no user reaches, which is how a whole redesign
        // came to be judged from pictures of the implementation it was replacing.
        java.util.Map<urncore.IURNNode, org.eclipse.draw2d.geometry.Point> placed =
                AutoLayoutWizard.placeUcmLayered((UCMmap) diagram);
        System.out.println("[layered] placed=" + placed.size() + " of " + ((UCMmap) diagram).getNodes().size());

        CompoundCommand cmd = AutoLayoutWizard.commandsFor(diagram, placed);
        // A map with no nodes has nothing to place, and producing no commands for it is correct.
        if (((UCMmap) diagram).getNodes().isEmpty())
            return;

        assertTrue("the layout should produce commands", !cmd.isEmpty()); //$NON-NLS-1$
        assertTrue("and they should be executable", cmd.canExecute()); //$NON-NLS-1$

        editor.getDelegatingCommandStack().execute(cmd);
        page.getGraphicalViewer().flush();
    }

    /**
     * Before and after, side by side on disk, for the model whose layout prompted all of this.
     *
     * The assertions are deliberately weak -- a file, non-trivially sized. The point is the
     * artefact, not the check.
     */
    @Test
    public void rendersTheIssueTrackerMapBeforeAndAfterLayout() throws Exception {
        open("IssueTrackerSyntheticLog_variant.jucm"); //$NON-NLS-1$

        File before = render("issuetracker-before"); //$NON-NLS-1$
        layoutCurrentPage();
        File after = render("issuetracker-after"); //$NON-NLS-1$

        assertTrue("nothing was rendered before layout", before.length() > 1000); //$NON-NLS-1$
        assertTrue("nothing was rendered after layout", after.length() > 1000); //$NON-NLS-1$
    }

    /**
     * Renders the same map under several layout settings, so they can be compared by eye.
     *
     * Not an assertion about which is best -- that is a judgement -- but the only way to make the
     * judgement is to have the pictures side by side.
     */
    @Test
    public void rendersLayoutVariantsForComparison() throws Exception {
        String[][] variants = {
                { "variant-TB-clusters", "TB", "false" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                { "variant-LR-clusters", "LR", "false" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                { "variant-LR-nocluster", "LR", "true" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                { "variant-TB-nocluster", "TB", "true" } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        for (int i = 0; i < variants.length; i++) {
            open("IssueTrackerSyntheticLog_variant.jucm"); //$NON-NLS-1$
            AutoLayoutPreferences.getPreferenceStore().setValue(AutoLayoutPreferences.PREF_ORIENTATION, variants[i][1]);
            System.setProperty("jucmnav.layout.nocluster", variants[i][2]); //$NON-NLS-1$

            layoutCurrentPage();
            render(variants[i][0]);

            editor.closeEditor(false);
            editor = null;
        }
        System.setProperty("jucmnav.layout.nocluster", "false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The smaller fork/join sample, where a bad curve is easier to spot by eye. */
    @Test
    public void rendersTheForkJoinMapBeforeAndAfterLayout() throws Exception {
        open("ExtractStub.jucm"); //$NON-NLS-1$

        File before = render("forkjoin-before"); //$NON-NLS-1$
        layoutCurrentPage();
        File after = render("forkjoin-after"); //$NON-NLS-1$

        assertTrue("nothing was rendered before layout", before.length() > 500); //$NON-NLS-1$
        assertTrue("nothing was rendered after layout", after.length() > 500); //$NON-NLS-1$
    }

    /**
     * Renders every UCM map of the models in a directory, before and after layout.
     *
     * <p>
     * A development tool, not a gate: point {@code -Djucmnav.demos=<dir>} at a folder of .jucm
     * files and it writes a before/after pair per map, so a change to the layout can be judged
     * against models a person drew by hand rather than against a synthetic one. Skipped when the
     * directory is absent, which is why it can live in the suite without depending on anyone's
     * disk.
     */
    @Test
    public void rendersDemoModelsForComparison() throws Exception {
        String dir = System.getProperty("jucmnav.demos"); //$NON-NLS-1$
        org.junit.Assume.assumeTrue("no -Djucmnav.demos directory given", dir != null); //$NON-NLS-1$

        File[] models = new File(dir).listFiles();
        org.junit.Assume.assumeTrue("demo directory not readable", models != null); //$NON-NLS-1$

        String only = System.getProperty("jucmnav.demos.only"); //$NON-NLS-1$

        for (int m = 0; m < models.length; m++) {
            String fileName = models[m].getName();
            if (!fileName.endsWith(".jucm")) //$NON-NLS-1$
                continue;
            // Comma-separated prefixes, so a sweep can name the models it cares about.
            if (only != null) {
                boolean wanted = false;
                String[] prefixes = only.split(","); //$NON-NLS-1$
                for (int w = 0; w < prefixes.length; w++)
                    wanted |= fileName.startsWith(prefixes[w].trim());
                if (!wanted)
                    continue;
            }

            IFile file = project.getFile(fileName);
            if (file.exists())
                file.delete(true, false, null);
            file.create(new java.io.FileInputStream(models[m]), false, null);

            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(fileName);
            editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(file), desc.getId());
            urn = editor.getModel();

            String stem = fileName.substring(0, fileName.length() - 5);
            int index = 0;
            for (Iterator<?> it = new java.util.ArrayList<Object>(urn.getUrndef().getSpecDiagrams()).iterator(); it.hasNext();) {
                IURNDiagram d = (IURNDiagram) it.next();
                if (!(d instanceof UCMmap))
                    continue;

                if (((UCMmap) d).getNodes().isEmpty())
                    continue; // nothing to draw, nothing to lay out

                index++;
                editor.setActivePage(d);
                render(stem + "-" + index + "-before"); //$NON-NLS-1$ //$NON-NLS-2$
                try {
                    layout(d);
                    render(stem + "-" + index + "-after"); //$NON-NLS-1$ //$NON-NLS-2$
                } catch (Exception e) {
                    System.out.println("[layout render] FAILED on " + stem + " map " + index + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }

            editor.closeEditor(false);
            editor = null;
        }
    }

    /** Diagnostics: what the decomposition made of each sample, printed for the log. */
    @Test
    public void reportsWhatTheDecompositionSees() throws Exception {
        open("IssueTrackerSyntheticLog_variant.jucm"); //$NON-NLS-1$

        for (Iterator<?> it = urn.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                System.out.println("[decomposition] " + new UcmPathDecomposition((UCMmap) d).describe()); //$NON-NLS-1$
        }
    }
}
