package seg.jUCMNav.model.commands.transformations.internal;

import java.util.Iterator;

import org.eclipse.gef.commands.CompoundCommand;

import seg.jUCMNav.model.commands.create.AddInBindingCommand;
import seg.jUCMNav.model.commands.create.AddOutBindingCommand;
import seg.jUCMNav.model.commands.create.AddPluginCommand;
import ucm.map.NodeConnection;
import ucm.map.PluginBinding;

/**
 * Binds each of the stub's paths to the plug-in endpoint that continues it.
 *
 * <p>
 * The pairing is not worked out here: {@link ExtractScopeIntoStubCommand} created each endpoint for
 * a specific boundary connection and remembers which. This command only records what that one
 * already knows.
 *
 * <p>
 * That is the point of the rewrite. The previous implementation built the plug-in map by
 * duplicating a traversal and built the stub from the debris of a deletion, so the two sides had no
 * common thread and the bindings had to be reconstructed afterwards by matching element names, with
 * a positional fallback when names did not line up.
 *
 * <p>
 * Built during execution rather than construction, because the endpoints do not exist until
 * {@code ExtractScopeIntoStubCommand} has run and the {@link PluginBinding} does not exist until
 * {@link AddPluginCommand} has.
 *
 * @author Claude
 */
public class BindExtractedStubCommand extends CompoundCommand {

    private final ExtractScopeIntoStubCommand extraction;
    private final AddPluginCommand pluginCommand;
    private boolean built = false;

    public BindExtractedStubCommand(ExtractScopeIntoStubCommand extraction, AddPluginCommand pluginCommand) {
        this.extraction = extraction;
        this.pluginCommand = pluginCommand;
    }

    public boolean canExecute() {
        return true;
    }

    /**
     * An empty compound did nothing, and undoing nothing succeeds. GEF returns false from
     * canUndo() / canRedo() on an empty command list, which would make the enclosing refactor
     * un-undoable -- the defect behind #28.
     */
    public boolean canUndo() {
        if (getCommands().isEmpty())
            return true;

        return super.canUndo();
    }

    /**
     * @see #canUndo()
     */
    public boolean canRedo() {
        if (getCommands().isEmpty())
            return true;

        return super.canRedo();
    }

    public void execute() {
        if (!built) {
            PluginBinding binding = pluginCommand.getPlugin();
            if (binding != null) {
                for (Iterator<NodeConnection> it = extraction.getEntryConnections().iterator(); it.hasNext();) {
                    NodeConnection entry = it.next();
                    add(new AddInBindingCommand(binding, extraction.getEntryPoint(entry), entry));
                }
                for (Iterator<NodeConnection> it = extraction.getExitConnections().iterator(); it.hasNext();) {
                    NodeConnection exit = it.next();
                    add(new AddOutBindingCommand(binding, extraction.getExitPoint(exit), exit));
                }
            }
            built = true;
        }
        super.execute();
    }
}
