package seg.jUCMNav.model.commands.change;

import org.eclipse.gef.commands.Command;

import seg.jUCMNav.Messages;
import seg.jUCMNav.model.commands.JUCMNavCommand;
import ucm.map.NodeConnection;

/**
 * Changes the probability of a NodeConnection (an OR-fork branch). Mirrors
 * {@link ChangePluginBindingProbCommand} (which does the same for dynamic-stub
 * plug-in bindings). Used by the Condition Editor to make branch probabilities
 * editable and undoable (issue #22).
 *
 * @author Claude (QA modernization, issue #22)
 */
public class ChangeProbabilityCommand extends Command implements JUCMNavCommand {

    private NodeConnection nodeConnection;
    private double oldProbability;
    private double newProbability;

    public ChangeProbabilityCommand(NodeConnection nc, double prob) {
        super();
        this.nodeConnection = nc;
        this.newProbability = prob;
        this.oldProbability = nc.getProbability();
        setLabel(Messages.getString("ChangeProbabilityCommand.ChangeBranchProbability")); //$NON-NLS-1$
    }

    public void execute() {
        redo();
    }

    public boolean canExecute() {
        return nodeConnection != null;
    }

    public void redo() {
        testPreConditions();
        nodeConnection.setProbability(newProbability);
        testPostConditions();
    }

    public void undo() {
        testPostConditions();
        nodeConnection.setProbability(oldProbability);
        testPreConditions();
    }

    public void testPostConditions() {
        assert nodeConnection != null : "Post NodeConnection is null"; //$NON-NLS-1$
        assert nodeConnection.getProbability() == newProbability : "Post newProbability changed"; //$NON-NLS-1$
    }

    public void testPreConditions() {
        assert nodeConnection != null : "Pre NodeConnection is null"; //$NON-NLS-1$
        assert nodeConnection.getProbability() == oldProbability : "Pre newProbability changed"; //$NON-NLS-1$
    }

    public double getProbability() {
        return newProbability;
    }

    public void setProbability(double probability) {
        this.newProbability = probability;
    }

    public NodeConnection getNodeConnection() {
        return nodeConnection;
    }
}
