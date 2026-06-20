package seg.jUCMNav.tests.commands;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.ui.parts.ScrollingGraphicalViewer;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.editors.UrnEditor;
import seg.jUCMNav.model.ModelCreationFactory;
import ucm.map.ComponentRef;
import ucm.map.StartPoint;
import ucm.map.UCMmap;
import urn.URNspec;

/**
 * Plain (non-JUnit) fixture that opens a jUCMNav editor on a scratch .jucm file
 * and exposes the core model handles the command tests build on.
 *
 * Extracted from {@code JUCMNavCommandTests} during the JUnit 3 -> 4 migration
 * (issue #8) so that several test classes -- notably {@code ScenarioTraversalTests}
 * -- can share the same setup without the old {@code new JUCMNavCommandTests()}
 * friendship-via-inheritance hack. It carries no JUnit annotations; the owning
 * test class drives {@link #initjucmnav()} / {@link #cleanup()} from its own
 * {@code @Before} / {@code @After}.
 *
 * @author Claude (QA modernization, issue #8)
 */
public class JUCMNavTestFixture {

    public UCMNavMultiPageEditor editor;
    public CommandStack cs;
    public URNspec urnspec;
    public ComponentRef compRef;
    public StartPoint start;
    public UCMmap map;
    public IFile testfile;

    /** Opens a fresh editor on an empty scratch model and wires up the handles. */
    public void initjucmnav() throws CoreException, PartInitException {
        IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        IProject testproject = workspaceRoot.getProject("jUCMNav-tests"); //$NON-NLS-1$
        if (!testproject.exists())
            testproject.create(null);

        if (!testproject.isOpen())
            testproject.open(null);

        testfile = testproject.getFile("jUCMNav-test.jucm"); //$NON-NLS-1$

        // start with clean file
        if (testfile.exists())
            testfile.delete(true, false, null);

        testfile.create(new ByteArrayInputStream("".getBytes()), false, null); //$NON-NLS-1$

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(testfile.getName());
        editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(testfile), desc.getId());

        // generate a top level model element
        urnspec = editor.getModel();

        compRef = (ComponentRef) ModelCreationFactory.getNewObject(urnspec, ComponentRef.class);
        start = (StartPoint) ModelCreationFactory.getNewObject(urnspec, StartPoint.class);
        map = (UCMmap) urnspec.getUrndef().getSpecDiagrams().get(0);

        cs = editor.getDelegatingCommandStack();
    }

    /**
     * Closes the editor and disposes the command stack. Safe to call even if a
     * test failed partway through, so a single failing test cannot leave its
     * editor open and contaminate the shared workspace for later tests.
     */
    public void cleanup() {
        if (editor != null) {
            try {
                ((ScrollingGraphicalViewer) ((UrnEditor) editor.getActiveEditor()).getGraphicalViewer()).flush();
            } catch (RuntimeException ignore) {
                // best-effort viewer flush during teardown
            }
            editor.closeEditor(false);
        }
        editor = null;
        if (cs != null)
            cs.dispose();
        cs = null;
        urnspec = null;
        compRef = null;
        start = null;
        map = null;
        testfile = null;
    }
}
