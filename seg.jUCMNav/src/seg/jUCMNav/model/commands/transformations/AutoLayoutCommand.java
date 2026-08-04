package seg.jUCMNav.model.commands.transformations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import seg.jUCMNav.model.commands.IScopedGlobalCommand;
import urncore.IURNDiagram;

/**
 * One auto-layout, however many diagrams it rearranged.
 *
 * <p>
 * Laying out a whole model used to execute one {@code CompoundCommand} per diagram, so a model with
 * nine diagrams took nine presses of Ctrl+Z to put back, each one leaving the model in a state
 * nobody asked for -- some diagrams laid out, the rest not. A single user action should be a single
 * undo, and auto-layout is one action no matter how much it touched.
 *
 * <p>
 * Being a {@link IScopedGlobalCommand} puts this on the {@code DelegatingCommandStack}'s URN-spec
 * stack, which is where commands that span diagrams belong: a per-page stack cannot own an edit to
 * a page it does not represent. Naming the diagrams it touched is what lets that undo survive an
 * ordinary edit somewhere else in the model, rather than being discarded by the first thing the
 * user does afterwards.
 *
 * <p>
 * The scope is exact and it has to be: every diagram this reports is one whose nodes it moved. A
 * diagram left out would be one the stack believes is untouched, and undoing across an edit there
 * is precisely the "chart displayed in disorder" that scoping exists to prevent.
 *
 * @author Claude
 */
public class AutoLayoutCommand extends CompoundCommand implements IScopedGlobalCommand {

    /** Every diagram whose nodes this moved, in the order they were laid out. */
    private final List<IURNDiagram> affected = new ArrayList<IURNDiagram>();

    /** The diagram the user was looking at, so undo can take them back to it. */
    private final IURNDiagram primary;

    public AutoLayoutCommand(String label, IURNDiagram primary) {
        super(label);
        this.primary = primary;
    }

    /**
     * Adds the commands that lay out one diagram, and records that the diagram was touched.
     *
     * @param diagram
     *            the diagram {@code command} rearranges
     * @param command
     *            everything that moves or resizes something on it
     */
    public void add(IURNDiagram diagram, Command command) {
        if (command == null || diagram == null)
            return;

        affected.add(diagram);
        super.add(command);
    }

    /**
     * The diagram to show when this is undone or redone.
     *
     * The one the user had open if it was among those laid out, since that is where they will be
     * looking; otherwise the first, which is better than nothing to show.
     */
    public IURNDiagram getAffectedDiagram() {
        if (primary != null && affected.contains(primary))
            return primary;
        return affected.isEmpty() ? null : affected.get(0);
    }

    /** Every diagram rearranged. Deduplicated, since a diagram contributes at most one scope entry. */
    public Collection<IURNDiagram> getAffectedDiagrams() {
        Set<IURNDiagram> diagrams = new LinkedHashSet<IURNDiagram>(affected);
        return Collections.unmodifiableSet(diagrams);
    }

    /** How many diagrams this laid out. */
    public int diagramCount() {
        return getAffectedDiagrams().size();
    }
}
