package seg.jUCMNav.editors;

import java.util.ArrayList;
import java.util.EventObject;
import java.util.Iterator;
import java.util.List;

import org.eclipse.gef.EditPart;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CommandStackEvent;
import org.eclipse.gef.commands.CommandStackEventListener;
import org.eclipse.gef.commands.CommandStackListener;
import org.eclipse.ui.PartInitException;

import seg.jUCMNav.editors.actionContributors.ModeComboContributionItem;
import seg.jUCMNav.editparts.URNRootEditPart;
import seg.jUCMNav.model.commands.transformations.ChangeUCMDiagramOrderCommand;
import ucm.map.UCMmap;
import urncore.IURNDiagram;

/**
 * This class listens for command stack changes of the pages contained in this editor. It decides if the editor is dirty or not and refreshes the pages if maps
 * are added/removed.
 * 
 * @author Gunnar Wagenknecht, jkealey
 */
public class MultiPageCommandStackListener implements CommandStackListener, CommandStackEventListener {

    private final UCMNavMultiPageEditor editor;

    /**
     * @param editor
     *            jUCMNav
     */
    MultiPageCommandStackListener(UCMNavMultiPageEditor editor) {
        this.editor = editor;
    }

    /** the observed command stacks */
    private List commandStacks = new ArrayList(2);

    /**
     * Adds a <code>CommandStack</code> to observe.
     * 
     * @param commandStack
     */
    public void addCommandStack(CommandStack commandStack) {
        commandStacks.add(commandStack);
        // Register as a CommandStackEventListener (not the legacy CommandStackListener)
        // on the per-page stacks so we receive the event detail (POST_EXECUTE vs
        // POST_UNDO/REDO/MARK_SAVE) and can flush the URN-spec stack ONLY on a real
        // execute -- see commandStackVerifyPages / issue #6.
        commandStack.addCommandStackEventListener(this);
    }

    /**
     * What should be done when the stack changes.
     * 
     * @param event
     *            the command stack changed event.
     * 
     * @see org.eclipse.gef.commands.CommandStackListener#commandStackChanged(java.util.EventObject)
     */
    /**
     * Legacy {@link CommandStackListener} entry point. Only the
     * {@link DelegatingCommandStack} still notifies this way (it forwards its
     * underlying stack changes via the no-arg notifyListeners()). Those events
     * never trigger a URN-spec flush -- the source is the DelegatingCommandStack
     * itself -- so no event detail is needed here; we pass NO_DETAIL.
     *
     * @see org.eclipse.gef.commands.CommandStackListener#commandStackChanged(java.util.EventObject)
     */
    public void commandStackChanged(EventObject event) {
        handleStackChange(event, NO_DETAIL);
    }

    /**
     * {@link CommandStackEventListener} entry point for the per-page command
     * stacks. The detail flag lets us flush the URN-spec stack only on a real
     * execute, not on undo / redo / save / flush (issue #6). We react once per
     * operation, on the POST event.
     *
     * @see org.eclipse.gef.commands.CommandStackEventListener#stackChanged(org.eclipse.gef.commands.CommandStackEvent)
     */
    public void stackChanged(CommandStackEvent event) {
        if (!event.isPostChangeEvent())
            return;
        handleStackChange(event, event.getDetail());
    }

    /** Sentinel for the legacy listener path, which carries no event detail. */
    private static final int NO_DETAIL = -1;

    private void handleStackChange(EventObject event, int detail) {
        if (((CommandStack) event.getSource()).isDirty()) {
            // at least one command stack is dirty,
            // so the multi page editor is dirty too
            this.editor.setDirty(true);
        } else {
            // probably a save, we have to check all command stacks
            boolean oneIsDirty = false;
            for (Iterator stacks = commandStacks.iterator(); stacks.hasNext();) {
                CommandStack stack = (CommandStack) stacks.next();
                if (stack.isDirty()) {
                    oneIsDirty = true;
                    break;
                }
            }
            this.editor.setDirty(oneIsDirty);
        }

        // update the contextual menus / toolbars
        this.editor.getActionRegistryManager().updateStackActions();

        // check to see if there are any new/deleted pages; will have to update tabs.
        commandStackVerifyPages(event, detail);
    }

    /**
     * Updates the editor when a new page is added/removed. Keeps the open editors in synch with the omdel.
     * 
     * @param event
     *            the command stack changed event.
     */
    private void commandStackVerifyPages(EventObject event, int detail) {
        if (this.editor.getPageCount() != this.editor.getModel().getUrndef().getSpecDiagrams().size() && event.getSource() instanceof DelegatingCommandStack) {
            IURNDiagram diagramChanged = ((DelegatingCommandStack) event.getSource()).getLastAffectedDiagram();

            // was added
            if (this.editor.getModel().getUrndef().getSpecDiagrams().contains(diagramChanged)) {
                if (this.editor.getPageCount() <  this.editor.getModel().getUrndef().getSpecDiagrams().size())
                    createEditor(diagramChanged);

            } else // was deleted
            {
                if (this.editor.getPageCount() >  this.editor.getModel().getUrndef().getSpecDiagrams().size() )
                    removeEditor(diagramChanged);
            }
        } else {
            // A change came directly from a per-page command stack (not the
            // DelegatingCommandStack). The URN-spec stack holds create/delete-map
            // commands; the UX rule is that one can no longer be undone once a
            // normal edit is performed afterward, so we flush it here. But flush
            // ONLY on a real execute (POST_EXECUTE): flushing on undo / redo /
            // save / flush previously wiped redo capability across a save, which
            // is the root cause of issue #6. The DelegatingCommandStack.execute
            // path already flushes on execute, so this is the secondary guard.
            if (!(event.getSource() instanceof DelegatingCommandStack)) {
                if (detail == CommandStack.POST_EXECUTE)
                    this.editor.getDelegatingCommandStack().flushURNspecStack();
            }
            else
            {
                if (this.editor.getDelegatingCommandStack().getRedoCommand() instanceof ChangeUCMDiagramOrderCommand ||  this.editor.getDelegatingCommandStack().getUndoCommand() instanceof ChangeUCMDiagramOrderCommand)
                {
                    IURNDiagram diag =this.editor.getDelegatingCommandStack().getLastAffectedDiagram();
                    if (diag.getUrndefinition()!=null)
                    {
                        int modelIndex = diag.getUrndefinition().getSpecDiagrams().indexOf(diag);
                        if (((UrnEditor)editor.getEditor(modelIndex)).getModel()!=diag) {
                            removeEditor(diag);
                            // might get done by notifications
                            if (this.editor.getPageCount() != diag.getUrndefinition().getSpecDiagrams().size())
                                commandStackVerifyPages(event, detail);
                        }
                    }
                }
            }

        }
    }

    private void removeEditor(IURNDiagram diagramChanged) {
        int i;
        for (i = 0; i < this.editor.getPageCount(); i++) {
            if (((UrnEditor) this.editor.getEditor(i)).getModel().equals(diagramChanged)) {
                // remove command stacks
                this.editor.getMultiPageCommandStackListener().removeCommandStack(((UrnEditor) this.editor.getEditor(i)).getCommandStack());
                this.editor.removePage(i);

                break;
            }
        }

        if (diagramChanged != null)
            diagramChanged.eAdapters().remove(this.editor);

        this.editor.getMultiPageTabManager().currentPageChanged();
    }

    private void createEditor(IURNDiagram diagramChanged) {
        UrnEditor u = null;
        if (diagramChanged instanceof UCMmap) {
            u = new UcmEditor(this.editor);
        } else { // if(diagramChanged instanceof GRLGraph){
            u = new GrlEditor(this.editor);
        }
        u.setModel(diagramChanged);

        try {
            this.editor.addPage(this.editor.getModel().getUrndef().getSpecDiagrams().indexOf(diagramChanged), u, this.editor.getEditorInput());
        } catch (PartInitException e) {
            e.printStackTrace();
        }

        // add command stacks
        this.editor.getMultiPageCommandStackListener().addCommandStack(u.getCommandStack());

        diagramChanged.eAdapters().add(this.editor);

        // set the mode to that already in use
        if (!ModeComboContributionItem.isLocal() && this.editor.getPageCount() >= 1) {
            int mode = ((URNRootEditPart) ((UrnEditor) this.editor.getEditor(0)).getGraphicalViewer().getRootEditPart()).getMode();
            ((URNRootEditPart) u.getGraphicalViewer().getRootEditPart()).setMode(mode);
        }

        this.editor.getMultiPageTabManager().refreshPageNames();
        this.editor.setActivePage(this.editor.getModel().getUrndef().getSpecDiagrams().indexOf(diagramChanged));
        u.getGraphicalViewer().select((EditPart) u.getGraphicalViewer().getEditPartRegistry().get(diagramChanged));
    }

    /**
     * Disposed the listener
     */
    public void dispose() {
        for (Iterator stacks = commandStacks.iterator(); stacks.hasNext();) {
            ((CommandStack) stacks.next()).removeCommandStackEventListener(this);
        }
        commandStacks.clear();
    }

    /**
     * Marks every observed command stack beeing saved. This method should be called whenever the editor/model was saved.
     */
    public void markSaveLocations() {
        for (Iterator stacks = commandStacks.iterator(); stacks.hasNext();) {
            CommandStack stack = (CommandStack) stacks.next();
            stack.markSaveLocation();
        }

        // bug 447
        if (editor != null && editor.getDelegatingCommandStack() != null)
            editor.getDelegatingCommandStack().markSaveLocation();
    }

    /**
     * Removes a <code>CommandStack</code> that was observed.
     * 
     * @param commandStack
     */
    public void removeCommandStack(CommandStack commandStack) {
        commandStacks.remove(commandStack);
        commandStack.removeCommandStackEventListener(this);
    }
}