package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.model.commands.IGlobalStackCommand;
import seg.jUCMNav.model.commands.IScopedGlobalCommand;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintCommand;
import seg.jUCMNav.model.commands.transformations.AutoLayoutCommand;
import seg.jUCMNav.views.wizards.AutoLayoutWizard;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urn.URNspec;
import urncore.IURNDiagram;
import urncore.IURNNode;
import urncore.URNmodelElement;

/**
 * Auto-layout is one user action, so it has to be one undo.
 *
 * <p>
 * Laying out a whole model executed one command per diagram, which meant a nine-diagram model took
 * nine presses of Ctrl+Z to put back -- and every press in between left some diagrams laid out and
 * the rest not, a state the user never asked for and could not have produced by hand.
 *
 * <p>
 * Pure model and pure commands: no editor is opened, so this runs in milliseconds and says nothing
 * about the workbench. What it pins down is the command contract -- one command, exact scope, and a
 * round trip that puts every diagram back where it started.
 *
 * @author Claude
 */
public class AutoLayoutUndoTest {

    private URNspec sample;
    private UCMmap sampleMap;
    private grl.GRLGraph secondDiagram;

    @Before
    public void loadSample() throws Exception {
        ucm.map.impl.MapPackageImpl.init();
        ResourceSet rs = new ResourceSetImpl();
        rs.getResourceFactoryRegistry().getExtensionToFactoryMap().put("jucm", new XMIResourceFactoryImpl()); //$NON-NLS-1$
        Resource r = rs.createResource(URI.createURI("sample.jucm")); //$NON-NLS-1$
        r.load(AutoLayoutUndoTest.class.getResourceAsStream("/seg/jUCMNav/tests/commands/IssueTrackerSyntheticLog_variant.jucm"), //$NON-NLS-1$
                new HashMap<Object, Object>());

        sample = (URNspec) r.getContents().get(0);
        for (Iterator it = sample.getUrndef().getSpecDiagrams().iterator(); it.hasNext();) {
            IURNDiagram d = (IURNDiagram) it.next();
            if (d instanceof UCMmap)
                sampleMap = (UCMmap) d;
        }
        assertNotNull("the sample should hold a UCM map", sampleMap); //$NON-NLS-1$

        // A second diagram, so "several diagrams" is actually exercised rather than assumed.
        secondDiagram = grl.GrlFactory.eINSTANCE.createGRLGraph();
        ((URNmodelElement) secondDiagram).setId("9000"); //$NON-NLS-1$
        sample.getUrndef().getSpecDiagrams().add(secondDiagram);
        for (int i = 0; i < 3; i++) {
            grl.IntentionalElementRef ref = grl.GrlFactory.eINSTANCE.createIntentionalElementRef();
            ref.setId("900" + (i + 1)); //$NON-NLS-1$
            ref.setX(10 * i);
            ref.setY(20 * i);
            secondDiagram.getNodes().add(ref);
        }
    }

    // -------------------------------------------------------------------------- the round trip

    /**
     * The test the change exists to pass: one undo, and every diagram is back.
     */
    @Test
    public void oneUndoPutsEveryDiagramBack() {
        List<IURNNode> nodes = allNodes();
        int[] originalX = new int[nodes.size()], originalY = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            originalX[i] = nodes.get(i).getX();
            originalY[i] = nodes.get(i).getY();
        }

        Command layout = AutoLayoutWizard.compose(twoDiagramCommands(), diagrams(), sampleMap);

        assertTrue("it must be executable", layout.canExecute()); //$NON-NLS-1$
        layout.execute();

        // Everything actually moved, otherwise the undo below would prove nothing.
        for (int i = 0; i < nodes.size(); i++)
            assertEquals("node " + i + " should have been moved", 7000 + i, nodes.get(i).getX()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("one undo must be available", layout.canUndo()); //$NON-NLS-1$
        layout.undo();

        for (int i = 0; i < nodes.size(); i++) {
            assertEquals("one undo must restore x on every diagram, node " + i, originalX[i], nodes.get(i).getX()); //$NON-NLS-1$
            assertEquals("one undo must restore y on every diagram, node " + i, originalY[i], nodes.get(i).getY()); //$NON-NLS-1$
        }

        // And redo puts it back, so the history is symmetric rather than one-way.
        layout.redo();
        for (int i = 0; i < nodes.size(); i++)
            assertEquals("redo must reapply the layout, node " + i, 7000 + i, nodes.get(i).getX()); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------------- the scope

    /**
     * A multi-diagram layout goes on the global stack and names every diagram it moved.
     *
     * The naming is what lets its undo survive an ordinary edit elsewhere in the model; a diagram
     * left out would be one the stack believes untouched, which is how an undo comes to be applied
     * to a model that no longer matches it.
     */
    @Test
    public void namesEveryDiagramItTouched() {
        Command layout = AutoLayoutWizard.compose(twoDiagramCommands(), diagrams(), sampleMap);

        assertTrue("several diagrams must produce a global-stack command", layout instanceof IGlobalStackCommand); //$NON-NLS-1$
        assertTrue("and it must declare its scope", layout instanceof IScopedGlobalCommand); //$NON-NLS-1$

        AutoLayoutCommand all = (AutoLayoutCommand) layout;
        assertEquals("both diagrams must be named", 2, all.getAffectedDiagrams().size()); //$NON-NLS-1$
        assertTrue("the map must be named", all.getAffectedDiagrams().contains(sampleMap)); //$NON-NLS-1$
        assertTrue("the second diagram must be named", all.getAffectedDiagrams().contains(secondDiagram)); //$NON-NLS-1$
    }

    /** Undo should return the user to the diagram they were looking at, when it was one of them. */
    @Test
    public void comesBackToTheDiagramTheUserWasOn() {
        AutoLayoutCommand all = (AutoLayoutCommand) AutoLayoutWizard.compose(twoDiagramCommands(), diagrams(), secondDiagram);

        assertSame("the diagram the user had open", secondDiagram, all.getAffectedDiagram()); //$NON-NLS-1$
    }

    /**
     * One diagram stays an ordinary compound command on its own page's stack.
     *
     * <p>
     * That is already a single undo, and a page stack keeps it until it is undone. Making it global
     * would park it on the URN-spec stack, where an unrelated edit can discard it -- so wrapping
     * the common case would cost the user their undo to fix a problem the common case does not
     * have.
     */
    @Test
    public void aSingleDiagramIsNotPushedOntoTheGlobalStack() {
        List<CompoundCommand> one = new ArrayList<CompoundCommand>();
        one.add(commandsMoving(sampleMap, 0));
        List<IURNDiagram> justTheMap = new ArrayList<IURNDiagram>();
        justTheMap.add(sampleMap);

        Command layout = AutoLayoutWizard.compose(one, justTheMap, sampleMap);

        assertSame("a single diagram's command must be passed through untouched", one.get(0), layout); //$NON-NLS-1$
        assertTrue("and must not become a global command", !(layout instanceof IGlobalStackCommand)); //$NON-NLS-1$
    }

    /** A diagram that produced no commands is not in the scope, because nothing on it moved. */
    @Test
    public void aDiagramThatWasNotLaidOutIsNotNamed() {
        AutoLayoutCommand all = new AutoLayoutCommand("layout", sampleMap); //$NON-NLS-1$
        all.add(sampleMap, commandsMoving(sampleMap, 0));
        all.add(null, commandsMoving(secondDiagram, 100));
        all.add(secondDiagram, null);

        assertEquals("only the diagram that got commands", 1, all.getAffectedDiagrams().size()); //$NON-NLS-1$
        assertTrue(all.getAffectedDiagrams().contains(sampleMap));
    }

    // ---------------------------------------------------------------------------------- helpers

    private List<IURNDiagram> diagrams() {
        List<IURNDiagram> list = new ArrayList<IURNDiagram>();
        list.add(sampleMap);
        list.add(secondDiagram);
        return list;
    }

    private List<CompoundCommand> twoDiagramCommands() {
        List<CompoundCommand> commands = new ArrayList<CompoundCommand>();
        commands.add(commandsMoving(sampleMap, 0));
        commands.add(commandsMoving(secondDiagram, sampleMap.getNodes().size()));
        return commands;
    }

    /** Moves every node of a diagram somewhere recognisable, so a round trip can be checked. */
    private CompoundCommand commandsMoving(IURNDiagram diagram, int from) {
        CompoundCommand cmd = new CompoundCommand();
        int i = from;
        for (Iterator it = diagram.getNodes().iterator(); it.hasNext();) {
            IURNNode node = (IURNNode) it.next();
            cmd.add(new SetConstraintCommand(node, 7000 + i, 8000 + i));
            i++;
        }
        return cmd;
    }

    /** Every node of both diagrams, in the order the commands touch them. */
    private List<IURNNode> allNodes() {
        List<IURNNode> nodes = new ArrayList<IURNNode>();
        for (Iterator it = sampleMap.getNodes().iterator(); it.hasNext();)
            nodes.add((PathNode) it.next());
        for (Iterator it = secondDiagram.getNodes().iterator(); it.hasNext();)
            nodes.add((IURNNode) it.next());
        return nodes;
    }
}
