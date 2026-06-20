package seg.jUCMNav.tests.views;

import java.io.ByteArrayInputStream;
import java.util.EventObject;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

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
import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.views.property.tabbed.GEFTabbedPropertySheetPage;

/**
 * Regression test for the post-dispose guard in
 * {@link GEFTabbedPropertySheetPage#commandStackChanged(EventObject)}.
 *
 * The page registers itself as a {@code CommandStackListener} on the active
 * sub-editor's command stack (in {@code addSectionToRefresh}), but unregisters
 * via {@code StackHelper.getStack()}, which resolves through the *current*
 * active sub-editor. If the active UCM/GRL page changed in between, dispose()
 * removes the listener from a different stack and the original stack keeps
 * notifying the page after dispose() has nulled its state -- previously NPEing
 * on {@code sectionsToRefresh.iterator()}.
 *
 * This test reproduces the essential failure mode deterministically: a
 * commandStackChanged notification delivered after dispose() must be a safe
 * no-op rather than throwing. It does not attempt to recreate the exact
 * workbench timing race (which is not deterministic under JUnit); it locks in
 * the null-safety contract the guard provides.
 *
 * @author Claude (QA modernization)
 */
public class GEFTabbedPropertySheetPagePostDisposeTest {

    private UCMNavMultiPageEditor editor;

    @Before
    public void setUp() throws Exception {

        IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
        IProject testproject = workspaceRoot.getProject("jUCMNav-tests"); //$NON-NLS-1$
        if (!testproject.exists())
            testproject.create(null);
        if (!testproject.isOpen())
            testproject.open(null);

        org.eclipse.core.resources.IFile testfile = testproject.getFile("jUCMNav-propsheet-dispose-test.jucm"); //$NON-NLS-1$
        if (testfile.exists())
            testfile.delete(true, false, null);
        testfile.create(new ByteArrayInputStream("".getBytes()), false, null); //$NON-NLS-1$

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(testfile.getName());
        editor = (UCMNavMultiPageEditor) page.openEditor(new FileEditorInput(testfile), desc.getId());
    }

    @After
    public void tearDown() throws Exception {
        if (editor != null) {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            page.closeEditor(editor, false);
            editor = null;
        }
    }

    /**
     * A commandStackChanged() notification arriving after the page has been
     * disposed must not throw. Before the fix this NPEd because dispose() nulls
     * sectionsToRefresh while a stale stack could still deliver notifications.
     */
    @Test
    public void testCommandStackChangedAfterDisposeIsNullSafe() throws Exception {
        GEFTabbedPropertySheetPage propertyPage = new GEFTabbedPropertySheetPage(editor);
        CommandStack stack = editor.getDelegatingCommandStack();

        // Sanity: while alive, a notification with no registered sections is a no-op.
        propertyPage.commandStackChanged(new EventObject(stack));

        // Tear the page down (nulls sectionsToRefresh + editor).
        propertyPage.dispose();

        // A stale notification delivered post-dispose must be a safe no-op.
        try {
            propertyPage.commandStackChanged(new EventObject(stack));
        } catch (NullPointerException npe) {
            fail("commandStackChanged must be null-safe after dispose, but threw: " + npe); //$NON-NLS-1$
        }
    }
}
