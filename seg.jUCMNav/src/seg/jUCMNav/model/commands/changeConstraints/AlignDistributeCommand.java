package seg.jUCMNav.model.commands.changeConstraints;

import java.util.HashMap;
import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;

import urncore.IURNContainerRef;
import urncore.IURNNode;
import urncore.URNmodelElement;

public class AlignDistributeCommand extends CompoundCommand {

	//private static final int SELTYPE_INTENTIONALELEMENT = 1;
	protected static final int SELTYPE_ACTOR = 2;
	protected static final int SELTYPE_COMPONENT = 3;
	//private static final int SELTYPE_PATHNODE = 4;
	protected int selType;
	protected String moveType;
	//private URNspec urnspec;
	protected HashMap<String, HashMap <String, Integer>> coordinatesValues;
	protected HashMap<IURNContainerRef, HashMap <String, Integer>> coordinatesValuesContainer;
	protected List sel;
	protected boolean verticalMove;
	protected String axis = "x";

	public AlignDistributeCommand() {
		super();
	}

	public AlignDistributeCommand(String label) {
		super(label);
	}

	protected void getOldCoordinates() {
	// puts the old coordinates in a HashMap for each URNmodelElement
	if (Integer.valueOf(selType).compareTo(SELTYPE_ACTOR) == 0 ||
			Integer.valueOf(selType).compareTo(SELTYPE_COMPONENT) == 0){
			for(IURNContainerRef currentContainer : (List<IURNContainerRef>)sel){
				
				HashMap<String, Integer> coordinatesMap = new HashMap<String, Integer>();
				coordinatesMap.put("y", currentContainer.getY());
				coordinatesMap.put("x", currentContainer.getX());
				coordinatesValuesContainer.put( currentContainer, coordinatesMap);
			}
	}else{
		// puts the old coordinates in a HashMap for each URNmodelElement
			for(IURNNode currentNode : (List<IURNNode>)sel){
	
				HashMap<String, Integer> coordinatesMap = new HashMap<String, Integer>();
				coordinatesMap.put("y", currentNode.getY());
				coordinatesMap.put("x", currentNode.getX());
				coordinatesValues.put( ((URNmodelElement)currentNode).getId(), coordinatesMap);
			}
		}
	}

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
		return true;
	}

}