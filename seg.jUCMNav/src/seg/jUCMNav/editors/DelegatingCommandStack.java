/**
 * Eclipse Development using GEF and EMF: NetworkEditor example
 * 
 * (c) Copyright IBM Corp.
 *
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     Gunnar Wagenknecht - initial contribution
 * 
 */
package seg.jUCMNav.editors;

import java.util.EventObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CommandStackListener;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.gef.commands.UnexecutableCommand;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

import seg.jUCMNav.model.commands.IGlobalStackCommand;
import seg.jUCMNav.model.commands.IScopedGlobalCommand;
import seg.jUCMNav.model.commands.cutcopypaste.PasteCommand;
import seg.jUCMNav.views.outline.UrnOutlinePage;
import urncore.IURNDiagram;

/**
 * This is a delegating command stack, which delegates everything a defined the CommandStack except event listners.
 * 
 * <p>
 * Event listeners registered to a <code>DelegatingCommandStack</code> will be informed whenever the underlying <code>CommandStack</code> changes. They will not
 * be registered to the underlying <code>CommandStack</code> but they will be informed about change events of them.
 * 
 * All ugly stkUrnSpec related code added by jkealey. This code is to allow DeleteMapCommands/CreateMapCommands to be undone because they can't be executed in
 * one of the UcmEditor's command stacks.
 * 
 * @author Gunnar Wagenknecht, jkealey
 */
public class DelegatingCommandStack extends CommandStack implements CommandStackListener {
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[] {};
    /** the current command stack */
    private CommandStack currentCommandStack;
    private IURNDiagram lastAffectedDiagram;

    /** The diagram whose page owns {@link #currentCommandStack}, when the caller told us. */
    private IURNDiagram currentDiagram;

    /**
     * Diagrams the parked global commands touched, or null when at least one of them would not say.
     * Null means "assume everything", which is the safe reading and the historical behaviour.
     */
    private Set<IURNDiagram> globallyAffected = null;

    /**
     * How many commands each page stack has run since the newest global command was parked.
     *
     * The two stacks have no shared ordering, and the URN-spec stack is consulted first, so without
     * this a parked command that outlives an edit would be undone before it -- oldest first, which
     * is not what Undo means. A page stack with edits newer than the parked command therefore wins
     * until those are exhausted.
     */
    private Map<CommandStack, Integer> editsSincePark = new HashMap<CommandStack, Integer>();

    // some of our commands add/delete map don't belong in any of the editor stacks.
    // this stack is only available if the last execute was a DeleteMapCommand or a CreateMapCommand. it is flushed after that.
    private CommandStack stkUrnSpec;
    private boolean unsavedChanges = false;

    /**
     * Creates a stack that delegates to another stack. This stack can be registered once and have its behaviour change dynamically.
     */
    public DelegatingCommandStack() {
        stkUrnSpec = new CommandStack();
        stkUrnSpec.addCommandStackListener(this);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#canRedo()
     */
    public boolean canRedo() {

        // Ask the parked command whether it can redo, rather than only whether one is parked.
        // See canUndo() for why the answer must not fall through to the page stack.
        if (stkUrnSpec.getRedoCommand() != null)
            return stkUrnSpec.canRedo();
        else {
            if (null == currentCommandStack)
                return false;
            else
                return currentCommandStack.canRedo();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#canUndo()
     */
    public boolean canUndo() {
        // A newer edit on the current page is undone before the parked command; see
        // editsSincePark.
        if (pageHasNewerEdits())
            return true;

        if (stkUrnSpec.getUndoCommand() != null)
            // A command is parked on the URN-spec stack. Undo is available only if that command
            // can actually be undone: this used to answer "yes" merely because the stack was
            // non-empty, so when the parked command reported canUndo() == false the Undo action
            // stayed enabled and every press was a silent no-op -- CommandStack.undo() opens
            // with `if (!canUndo()) return;`. RefactorIntoStubCommand does exactly that, because
            // it nests conditional sub-commands that end up empty and GEF treats an empty
            // CompoundCommand as un-undoable (legacy bug 923 / #28).
            //
            // Deliberately NOT falling through to currentCommandStack here. The parked command
            // spans diagrams and has typically already deleted or moved nodes that the page
            // stack's commands were recorded against; undoing past it would apply inverse
            // operations to a model that no longer matches them. Reporting "cannot undo" is
            // both honest and the safe answer.
            return stkUrnSpec.canUndo();
        else {

            if (null == currentCommandStack)
                return false;

            return currentCommandStack.canUndo();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStackListener#commandStackChanged(java.util.EventObject)
     */
    public void commandStackChanged(EventObject event) {
        notifyListeners();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#dispose()
     */
    public void dispose() {
        if (null != currentCommandStack)
            currentCommandStack.dispose();
    }

    /**
     * 
     * If the command adds or removes a new diagram, it executes the command in a special stack that will refresh the UI properly.
     * 
     */
    public void execute(Command command) {
        if (command instanceof PasteCommand) {
            PasteCommand pasteCommand = (PasteCommand) command;
            pasteCommand.build(); // typically built later during execution.
        }

        boolean b = checkSimpleCommand(command);
        if (b)
            return;

        if (null != currentCommandStack) {
            flushURNspecStackUnlessClearOf(currentDiagram);
            currentCommandStack.execute(command);
            countEdit(currentCommandStack, 1);
        }
    }

    /**
     * Discards the global commands parked on the URN-spec stack, unless they provably cannot
     * interact with an edit on the given diagram.
     *
     * <p>
     * The parked command is discarded when it could, because undoing a command that spans diagrams
     * while a page stack holds later commands recorded against the elements it moved would apply
     * inverse operations to a model that no longer matches them -- the "chart is displayed in
     * disorder" of legacy projetseg-update#923.
     *
     * <p>
     * That reasoning only applies to the diagrams the parked command actually touched. An edit
     * anywhere else cannot reach its elements, so discarding the undo there costs the user their
     * history for nothing. Commands that declare their scope
     * ({@link seg.jUCMNav.model.commands.IScopedGlobalCommand}) get that distinction drawn; anything
     * that does not is assumed to affect everything, which is what this always did.
     */
    /**
     * Whether anything is parked on the URN-spec stack at all.
     *
     * Cheap, and worth asking before working out which diagram an edit belongs to: with nothing
     * parked there is nothing to flush, and that is the overwhelmingly common case -- every
     * ordinary edit in a session where no map has been created, deleted or refactored.
     */
    public boolean hasParkedGlobalCommands() {
        return stkUrnSpec.getCommands().length > 0;
    }

    public void flushURNspecStackUnlessClearOf(IURNDiagram edited) {
        if (couldInteractWith(edited))
            flushURNspecStack();
    }

    private boolean couldInteractWith(IURNDiagram edited) {
        if (stkUrnSpec.getCommands().length == 0)
            return false;

        // Some parked command would not name its scope, or we do not know which diagram the edit
        // is for. Either way there is nothing to reason with, so assume the worst.
        if (globallyAffected == null || edited == null)
            return true;

        return globallyAffected.contains(edited);
    }

    private boolean checkSimpleCommand(Command command) {

        if (command instanceof IGlobalStackCommand) {
            lastAffectedDiagram = ((IGlobalStackCommand) command).getAffectedDiagram();

            // Scope accumulates across everything parked at once, and starts fresh when the stack
            // does. One command that will not declare its scope makes the whole set unknowable.
            if (stkUrnSpec.getCommands().length == 0)
                globallyAffected = new HashSet<IURNDiagram>();

            if (globallyAffected != null && command instanceof IScopedGlobalCommand)
                globallyAffected.addAll(((IScopedGlobalCommand) command).getAffectedDiagrams());
            else
                globallyAffected = null;

            stkUrnSpec.execute(command);
            editsSincePark.clear();
            return true;
        }

        if (command instanceof CompoundCommand) {
            for (Iterator iter = ((CompoundCommand) command).getCommands().iterator(); iter.hasNext();) {
                Command internal = (Command) iter.next();

                // recurse
                boolean b = checkSimpleCommand(internal);
                if (b)
                    return true;
            }
        }

        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#flush()
     */
    public void flush() {
        if (null != currentCommandStack)
            currentCommandStack.flush();
    }

    /**
     * Clears the stack that is external to any of the individual editors.
     * 
     */
    public void flushURNspecStack() {

        if (stkUrnSpec.getCommands().length > 0) {
            if (stkUrnSpec.getUndoCommand() != null)
                unsavedChanges = true;
            stkUrnSpec.flush();
        }
        lastAffectedDiagram = null;
        globallyAffected = null;
        editsSincePark.clear();
    }

    private void countEdit(CommandStack stack, int delta) {
        Integer current = editsSincePark.get(stack);
        int updated = (current == null ? 0 : current.intValue()) + delta;
        if (updated <= 0)
            editsSincePark.remove(stack);
        else
            editsSincePark.put(stack, Integer.valueOf(updated));
    }

    /**
     * Whether the current page has edits newer than the parked global command, which must therefore
     * be undone before it.
     */
    private boolean pageHasNewerEdits() {
        return currentCommandStack != null && editsSincePark.containsKey(currentCommandStack) && currentCommandStack.canUndo();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#getCommands()
     */
    public Object[] getCommands() {
        if (null == currentCommandStack)
            return EMPTY_OBJECT_ARRAY;

        return currentCommandStack.getCommands();
    }

    /**
     * Returns the current <code>CommandStack</code>.
     * 
     * @return the current <code>CommandStack</code>
     */
    public CommandStack getCurrentCommandStack() {
        return currentCommandStack;
    }

    /**
     * 
     * @return the map for which the command stack was last changed.
     */
    public IURNDiagram getLastAffectedDiagram() {
        return lastAffectedDiagram;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#getRedoCommand()
     */
    public Command getRedoCommand() {
        if (stkUrnSpec.getRedoCommand() != null) {
            return stkUrnSpec.getRedoCommand();
        } else {
            if (null == currentCommandStack)
                return UnexecutableCommand.INSTANCE;

            return currentCommandStack.getRedoCommand();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#getUndoCommand()
     */
    public Command getUndoCommand() {
        if (pageHasNewerEdits())
            return currentCommandStack.getUndoCommand();

        if (stkUrnSpec.getUndoCommand() != null) {
            return stkUrnSpec.getUndoCommand();
        } else {

            if (null == currentCommandStack)
                return UnexecutableCommand.INSTANCE;

            return currentCommandStack.getUndoCommand();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#getUndoLimit()
     */
    public int getUndoLimit() {
        if (null == currentCommandStack)
            return -1;

        return currentCommandStack.getUndoLimit();
    }

    /**
     * @return A stack that is external to any of the individual editors.
     */
    public CommandStack getURNspecStack() {
        return stkUrnSpec;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#isDirty()
     */
    public boolean isDirty() {
        if (stkUrnSpec.getUndoCommand() != null || unsavedChanges) {
            return true;
        } else {

            if (null == currentCommandStack)
                return false;

            return currentCommandStack.isDirty();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#markSaveLocation()
     */
    public void markSaveLocation() {
        // GEF CommandStack.flush() empties BOTH the undo AND redo stacks, so the
        // previous `stkUrnSpec.flush()` here destroyed redo capability on every
        // save. canRedo() returned false immediately after doSave(), regardless
        // of whether anything was actually flushed -- which is what the test
        // suite's undo-save-redo-save round-trip in tearDown is checking. Marking
        // a save location should only record the position; explicit flushing is
        // available via flushURNspecStack() when truly needed.
        unsavedChanges = false;

        if (null != currentCommandStack)
            currentCommandStack.markSaveLocation();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#redo()
     */
    public void redo() {
        try {
            tryUnhookOutlineSelectionSynchronizer();

            if (stkUrnSpec.getRedoCommand() != null) {

                Command command = stkUrnSpec.getRedoCommand();

                if (command instanceof IGlobalStackCommand) {
                    lastAffectedDiagram = ((IGlobalStackCommand) command).getAffectedDiagram();
                }

                stkUrnSpec.redo();
            } else {
                if (null != currentCommandStack) {
                    currentCommandStack.redo();
                    if (stkUrnSpec.getCommands().length > 0)
                        countEdit(currentCommandStack, 1);
                }
            }
        } finally {
            tryHookOutlineSelectionSynchronizer();
        }
    }

    /**
     * Sets the current <code>CommandStack</code>.
     * 
     * @param stack
     *            the <code>CommandStack</code> to set
     */
    public void setCurrentCommandStack(CommandStack stack) {
        setCurrentCommandStack(stack, null);
    }

    /**
     * As {@link #setCurrentCommandStack(CommandStack)}, but also records which diagram the page
     * owning this stack is editing.
     *
     * Knowing that is what lets an ordinary edit be told apart from one that could interact with a
     * parked global command; without it every edit is assumed to interact, as it always was.
     */
    public void setCurrentCommandStack(CommandStack stack, IURNDiagram diagram) {
        currentDiagram = diagram;

        if (currentCommandStack == stack)
            return;

        // remove from old command stack
        if (null != currentCommandStack)
            currentCommandStack.removeCommandStackListener(this);

        // set new command stack
        currentCommandStack = stack;

        // watch new command stack
        if (currentCommandStack != null)
            currentCommandStack.addCommandStackListener(this);

        // the command stack changed
        notifyListeners();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#setUndoLimit(int)
     */
    public void setUndoLimit(int undoLimit) {
        if (null != currentCommandStack)
            currentCommandStack.setUndoLimit(undoLimit);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    public String toString() {
        return "DelegatingCommandStack(" + currentCommandStack + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.gef.commands.CommandStack#undo()
     */
    public void undo() {
        try {
            tryUnhookOutlineSelectionSynchronizer();

            if (pageHasNewerEdits()) {
                countEdit(currentCommandStack, -1);
                currentCommandStack.undo();
                return;
            }

            if (stkUrnSpec.getUndoCommand() != null) {
                Command command = stkUrnSpec.getUndoCommand();
                if (command instanceof IGlobalStackCommand) {
                    lastAffectedDiagram = ((IGlobalStackCommand) command).getAffectedDiagram();
                }

                stkUrnSpec.undo();
            } else {

                if (null != currentCommandStack)
                    currentCommandStack.undo();
            }
        } finally {
            tryHookOutlineSelectionSynchronizer();
        }
    }

    protected void tryUnhookOutlineSelectionSynchronizer() {
        /* Did not produce significant performance boost. 
         * UrnOutlinePage urnOutlinePage = getOutlinePage();
        if (urnOutlinePage != null)
            urnOutlinePage.unhookOutlineViewer(); // temporarily unhook selection synchronizer.
            */
    }

    protected void tryHookOutlineSelectionSynchronizer() {
        /* Did not produce significant performance boost. 
         * UrnOutlinePage urnOutlinePage = getOutlinePage();
        if (urnOutlinePage != null)
            urnOutlinePage.unhookOutlineViewer(); // rehook selection synchronizer.
            */
    }

    protected UrnOutlinePage getOutlinePage() {
        UrnOutlinePage urnOutlinePage = null;
        if (PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                && PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor() instanceof UCMNavMultiPageEditor) {
            UCMNavMultiPageEditor editor = (UCMNavMultiPageEditor) PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
            IContentOutlinePage outline = (IContentOutlinePage) editor.getAdapter(IContentOutlinePage.class);
            if (outline instanceof UrnOutlinePage) {
                urnOutlinePage = (UrnOutlinePage) outline;
            }
        }
        return urnOutlinePage;
    }
}