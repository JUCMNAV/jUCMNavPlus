package seg.jUCMNav.model.commands;

import java.util.Collection;

import urncore.IURNDiagram;

/**
 * A global-stack command that can say which diagrams it actually touched.
 *
 * <p>
 * {@link IGlobalStackCommand} commands are parked on the DelegatingCommandStack's URN-spec stack,
 * and the first ordinary edit afterwards discards them: a global command spans diagrams, so undoing
 * it once a page stack holds later commands recorded against the elements it moved would apply
 * inverse operations to a model that no longer matches them. Since a command that only says "I
 * affect this one diagram" cannot rule that out, the stack has to assume the worst of every edit.
 *
 * <p>
 * A command implementing this interface names every diagram it touched, so the stack can tell an
 * edit that could interact from one that cannot, and keep the undo alive across the latter. Not
 * implementing it is the safe default and keeps the old behaviour.
 *
 * @author Claude
 */
public interface IScopedGlobalCommand extends IGlobalStackCommand {

    /**
     * Every diagram this command read or wrote, including any it created.
     *
     * Undo of this command survives an edit on any diagram outside this set. Returning an
     * incomplete set is unsafe -- when in doubt, do not implement this interface.
     */
    public Collection<IURNDiagram> getAffectedDiagrams();
}
