package seg.jUCMNav.model.commands.transformations.internal;

import java.util.Iterator;
import java.util.Vector;

import org.eclipse.gef.commands.CompoundCommand;

import seg.jUCMNav.model.commands.transformations.CutAnyPathCommand;
import ucm.map.NodeConnection;
import ucm.map.UCMmap;

public class CutAnyPathIfStillExistsCommand extends CompoundCommand {

    protected UCMmap map;
    protected boolean built = false;
    protected Vector pathsToCut;

    /**
     * Delayed CutAnyPathCommand
     * 
     */
    public CutAnyPathIfStillExistsCommand(UCMmap map, Vector pathsToCut) {

        this.map = map;
        this.pathsToCut = pathsToCut;
    }

    public boolean canExecute() {
        return true;
    }

    /**
     * An empty compound means there was nothing left to cut, and undoing nothing always succeeds.
     *
     * GEF's CompoundCommand returns false from canUndo() when its command list is empty. Because
     * this command is nested unconditionally -- RefactorIntoStubCommand always adds one, whether
     * or not there is a path to cut -- that false propagated to the enclosing command, whose
     * canUndo() then reported false as well. CommandStack.undo() opens with
     * `if (!canUndo()) return;`, so the whole refactor could never be undone (legacy bug 923,
     * #28). canExecute() above is overridden for exactly the same reason.
     */
    public boolean canUndo() {
        if (getCommands().isEmpty())
            return true;

        return super.canUndo();
    }

    /**
     * @see #canUndo() -- CompoundCommand.canRedo() has the same empty-list rule.
     */
    public boolean canRedo() {
        if (getCommands().isEmpty())
            return true;

        return super.canRedo();
    }

    public void execute() {
        if (!built) {

            if (pathsToCut != null) {
                for (Iterator iterator = pathsToCut.iterator(); iterator.hasNext();) {
                    NodeConnection nc = (NodeConnection) iterator.next();
                    if (map != null && map.getUrndefinition() != null && nc.getDiagram() != null && nc.getSource() != null && nc.getTarget() != null) {
                        add(new CutAnyPathCommand(map, nc, nc.getSource().getX() + 20, nc.getSource().getY() + 20));
                    }
                }
            }
            built = true;
        }
        super.execute();
    }
}
