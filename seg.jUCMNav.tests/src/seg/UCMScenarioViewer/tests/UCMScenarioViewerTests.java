package seg.UCMScenarioViewer.tests;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWTError;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import seg.UCMScenarioViewer.UCMScenarioViewer;

public class UCMScenarioViewerTests {
    private UCMScenarioViewer editor;
    private IFile testfile;
    private IWorkbenchPage page;

    @Before
    public void setUp() throws Exception {

        IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        IProject testproject = workspaceRoot.getProject("ucmscenarioviewer-tests"); //$NON-NLS-1$
        if (!testproject.exists())
            testproject.create(null);

        if (!testproject.isOpen())
            testproject.open(null);

        String testFilename = "test.jucmscenarios"; //$NON-NLS-1$

        testfile = testproject.getFile(testFilename);
        // start with clean file
        if (testfile.exists())
            testfile.delete(true, false, null);

        testfile.create(UCMScenarioViewerTests.class.getResourceAsStream(testFilename), false, null);

        page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(testfile.getName());
        editor = (UCMScenarioViewer) page.openEditor(new FileEditorInput(testfile), desc.getId());
    }

    @Test
    public void testHandleProblem() throws Exception {
        // try to make it run out of handles.
        int i = 0;
        try {
            for (i = 0; i < 100; i++) {
                page.closeEditor(editor, false);
                IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(testfile.getName());
                editor = (UCMScenarioViewer) page.openEditor(new FileEditorInput(testfile), desc.getId());
            }
        } catch (SWTError error) {
            System.err.println("Out of handles after " + i + " iterations");
            error.printStackTrace();
        }

    }

    @After
    public void tearDown() throws Exception {
        page.closeEditor(editor, false);
    }

}
