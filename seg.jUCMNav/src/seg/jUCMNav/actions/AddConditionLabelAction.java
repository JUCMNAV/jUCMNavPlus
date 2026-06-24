package seg.jUCMNav.actions;

import java.util.Iterator;
import java.util.List;

import org.eclipse.gef.EditPart;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.ui.IWorkbenchPart;

import seg.jUCMNav.JUCMNavPlugin;
import seg.jUCMNav.model.commands.create.CreateLabelCommand;
import ucm.map.EndPoint;
import ucm.map.FailurePoint;
import ucm.map.NodeConnection;
import ucm.map.OrFork;
import ucm.map.PathNode;
import ucm.map.StartPoint;
import ucm.map.WaitingPlace;
import urncore.Condition;

/**
 * Adds a label to a PathNode or ComponentRef.
 * 
 * @author Jordan
 */
public class AddConditionLabelAction extends URNSelectionAction {
    public static final String ADDLABEL = "seg.jUCMNav.AddConditionLabel"; //$NON-NLS-1$

    /**
     * @param part
     */
    public AddConditionLabelAction(IWorkbenchPart part) {
        super(part);
        setId(ADDLABEL);
        setImageDescriptor(JUCMNavPlugin.getImageDescriptor("icons/label.gif")); //$NON-NLS-1$
    }

    /**
     * can only add labels if none already exist.
     * 
     * @return true, if calculate enabled
     */
    protected boolean calculateEnabled() {
        List parts = getSelectedObjects();
        if (parts.size() == 1 && parts.get(0) instanceof EditPart) {
            EditPart part = (EditPart) parts.get(0);

            if ((part.getModel() instanceof NodeConnection)) {
                return needsLabel(((NodeConnection) part.getModel()).getCondition());
            } else if (part.getModel() instanceof StartPoint) {
                return needsLabel(((StartPoint) part.getModel()).getPrecondition());
            } else if (part.getModel() instanceof EndPoint) {
                return needsLabel(((EndPoint) part.getModel()).getPostcondition());
            } else if ((part.getModel() instanceof OrFork) || (part.getModel() instanceof WaitingPlace) || (part.getModel() instanceof FailurePoint)) {
                for (Iterator iter = ((PathNode) part.getModel()).getSucc().iterator(); iter.hasNext();) {
                    if (needsLabel(((NodeConnection) iter.next()).getCondition()))
                        return true;
                }
            }
        }

        return false;
    }

    /**
     * A condition can be given a label only if it exists and does not already have a
     * (non-empty) one. Extracted from the per-element branches to fix an operator-
     * precedence bug: the StartPoint / EndPoint branches read
     * {@code c != null && c.getLabel() == null || c.getLabel().length() == 0}, where
     * {@code &&} binds tighter than {@code ||}, so a null pre/postcondition (e.g. an
     * EndPoint with no postcondition) reached {@code null.getLabel()} and NPEd on any
     * selection change. Only the NodeConnection branch was correctly parenthesized.
     *
     * @param c the condition, possibly null
     * @return true if a label can/should be added to it
     */
    public static boolean needsLabel(Condition c) {
        return c != null && (c.getLabel() == null || c.getLabel().length() == 0);
    }

    /**
     * @return a {@link CreateLabelCommand} adapted to the situation.
     */
    protected Command getCommand() {
        List parts = getSelectedObjects();
        EditPart part = (EditPart) parts.get(0);

        if ((part.getModel() instanceof NodeConnection)) {
            NodeConnection nc = (NodeConnection) part.getModel();
            if (needsLabel(nc.getCondition()))
                return new CreateLabelCommand(nc.getCondition());
        } else if (part.getModel() instanceof StartPoint) {
            StartPoint point = (StartPoint) part.getModel();
            if (needsLabel(point.getPrecondition())) {
                return new CreateLabelCommand(point.getPrecondition());
            }
        } else if (part.getModel() instanceof EndPoint) {
            EndPoint point = (EndPoint) part.getModel();
            if (needsLabel(point.getPostcondition())) {
                return new CreateLabelCommand(point.getPostcondition());
            }
        } else if ((part.getModel() instanceof OrFork) || (part.getModel() instanceof WaitingPlace) || (part.getModel() instanceof FailurePoint)) {
            //UCMmodelElement modelElement = (UCMmodelElement) part.getModel();

            CompoundCommand cmd = new CompoundCommand();
            for (Iterator iter = ((PathNode) part.getModel()).getSucc().iterator(); iter.hasNext();) {
                NodeConnection nc = (NodeConnection) iter.next();
                if (needsLabel(nc.getCondition())) {
                    cmd.add(new CreateLabelCommand(nc.getCondition()));
                }
            }
            return cmd;
        }

        return null;
    }

}