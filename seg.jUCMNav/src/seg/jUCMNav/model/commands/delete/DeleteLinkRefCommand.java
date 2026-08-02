/**
 * 
 */
package seg.jUCMNav.model.commands.delete;

import grl.Contribution;
import grl.ContributionChange;
import grl.ContributionContext;
import grl.ElementLink;
import grl.LinkRef;
import grl.LinkRefBendpoint;

import java.util.Iterator;

import org.eclipse.gef.commands.CompoundCommand;

import seg.jUCMNav.Messages;
import seg.jUCMNav.editparts.dynamicContextTreeEditparts.DynamicContextsUtils;
import seg.jUCMNav.model.commands.delete.internal.RemoveElementLinkCommand;
import seg.jUCMNav.model.commands.delete.internal.RemoveLinkRefCommand;
import urn.dyncontext.Change;
import urn.dyncontext.DynamicContext;
import urncore.ConnectionLabel;

/**
 * Delete a LinkRef and all the LinkRefBendpoint associate to it. If it is the last linkref in the GRLGraphs, delete also the definition.
 * 
 * @author Jean-François Roy
 * 
 */
public class DeleteLinkRefCommand extends CompoundCommand {

    LinkRef linkref;
    ElementLink link;
    ConnectionLabel label;

    /**
     * @param ref
     *            The LinkRef to delete
     * 
     */
    public DeleteLinkRefCommand(LinkRef ref) {
        this.linkref = ref;
        this.link = linkref.getLink();
        setLabel(Messages.getString("DeleteLinkRefCommand.deleteLinkRef")); //$NON-NLS-1$
    }

    /**
     * Returns true even if no commands exist.
     */
    /**
     * An empty compound did nothing, and undoing nothing always succeeds.
     *
     * GEF's CompoundCommand returns false from canUndo() / canRedo() when its command list is
     * empty. That makes any enclosing compound un-undoable, and CommandStack.undo() opens with
     * `if (!canUndo()) return;`, so an empty one silently blocks undo instead of being a no-op.
     * canExecute() is already guarded for the same reason; this completes it (#28).
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

    public boolean canExecute() {
        if (getCommands().size() == 0)
            return true;
        else
            return super.canExecute();
    }

    /**
     * Late building
     */
    public void execute() {
        build();
        super.execute();
    }
    
    /**
     * Delete all the changes added to the Link
     */
    private void deleteChanges() {
    	
    	for (Iterator it = link.getGrlspec().getUrnspec().getDynamicContexts().iterator(); it.hasNext();) {
            DynamicContext dyn = (DynamicContext) it.next();
            
            //Delete Link Changes
            for (Iterator itEval = DynamicContextsUtils.getAllAvailableChanges(link, dyn, link.getGrlspec().getUrnspec()).iterator(); itEval.hasNext();) {
                Change change = (Change) itEval.next();
                if (change.getElement().equals(link)) {
                    add(new DeleteChangeCommand(change));
                }
            }
         
        }
    }

    /**
     * Builds a sequence of DeletePathNodeCommands
     * 
     */
    private void build() {
    	
    	int size = linkref.getBendpoints().size();
        for (int i = 0; i < size; i++) {
            LinkRefBendpoint bendpoint = (LinkRefBendpoint) linkref.getBendpoints().get(size - 1 - i);
            add(new DeleteLinkRefBendpointCommand(bendpoint));
        }

        add(new RemoveLinkRefCommand(linkref));
        if (!DeletionContext.isPerformingCutAction() && link != null && link.getRefs().size() <= 1 && link.getGrlspec() != null) {
            if (link instanceof Contribution) {
                for (Iterator iterator = link.getGrlspec().getContributionContexts().iterator(); iterator.hasNext();) {
                    ContributionContext context = (ContributionContext) iterator.next();
                    for (Iterator iterator2 = context.getChanges().iterator(); iterator2.hasNext();) {
                        ContributionChange change = (ContributionChange) iterator2.next();
                        if (change.getContribution() == (Contribution) link)
                            add(new DeleteContributionChangeCommand(change));
                    }
                }
                
            }
            
            //Delete all the changes added to the Link
            deleteChanges();
            add(new RemoveElementLinkCommand(link));
        }
    }

    public void setElementLink(ElementLink link) {
        for (int i = 0; i < getCommands().size(); i++) {
            if ((getCommands().get(i) instanceof RemoveLinkRefCommand)) {
                RemoveLinkRefCommand ref = (RemoveLinkRefCommand) getCommands().get(i);
                ref.setElementLink(link);
            }
        }
    }
}
