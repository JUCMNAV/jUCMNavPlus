package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import seg.jUCMNav.importexport.ExportLayoutDOT;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import urn.URNspec;
import urncore.URNmodelElement;

/**
 * What an actor's stored size does to the GRL drawing it is laid out into.
 *
 * <p>
 * {@code ExportLayoutDOT} used to reserve room for a container by emitting an invisible placeholder
 * node as big as the container currently is on screen -- {@code contRef.getWidth()/72.0} inches. An
 * actor that a user had dragged out to 1669px became a <b>23-inch</b> node, and Graphviz sized the
 * whole drawing around it. Everything real ended up in one corner of an enormous canvas, which is
 * the screenful of white space a user saw before their model came into view.
 *
 * <p>
 * No GRL or feature model was available to render, so the case is built here instead: one actor
 * with the stored bounds of a real component, holding three ordinary elements. The graph's own
 * bounding box, straight from {@code dot -Tplain}, is the measurement.
 *
 * @author Claude
 */
public class GrlClusterSizingTest {

    /** As wide as the widest component in the sample models to hand (PMM4RPA). */
    private static final int DRAGGED_OUT_WIDTH = 1669;
    private static final int DRAGGED_OUT_HEIGHT = 900;

    /**
     * The drawing must be sized by what the actor holds, not by how big the actor happens to be.
     *
     * <p>
     * Three GRL elements are about 150x85 each, so even spread across three ranks the graph has no
     * business being anywhere near 23 inches wide. Bounding the graph rather than asserting an
     * exact figure keeps this about the defect and not about Graphviz's spacing choices.
     */
    @Test
    public void anActorsStoredSizeDoesNotInflateTheDrawing() throws Exception {
        AutoLayoutPreferences.createPreferences();
        org.junit.Assume.assumeTrue("Graphviz not installed; the drawing cannot be measured", //$NON-NLS-1$
                AutoLayoutPreferences.locateDot() != null);

        String dot = ExportLayoutDOT.convertURNToDot(grlGraphWithABigActor());
        double[] box = graphBox(dot);

        System.out.println("GRL graph box: " + box[0] + " x " + box[1] + " inches"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        System.out.println(dot);

        assertTrue("the actor's 23-inch stored width must not size the drawing: " + box[0] + " inches wide", //$NON-NLS-1$ //$NON-NLS-2$
                box[0] < DRAGGED_OUT_WIDTH / 72.0 / 2);
    }

    /** A GRL graph whose single actor has been dragged out far larger than its contents need. */
    private static grl.GRLGraph grlGraphWithABigActor() {
        URNspec spec = urn.UrnFactory.eINSTANCE.createURNspec();
        spec.setUrndef(urncore.UrncoreFactory.eINSTANCE.createURNdefinition());

        grl.GRLGraph graph = grl.GrlFactory.eINSTANCE.createGRLGraph();
        ((URNmodelElement) graph).setId("800"); //$NON-NLS-1$
        spec.getUrndef().getSpecDiagrams().add(graph);

        grl.ActorRef actor = grl.GrlFactory.eINSTANCE.createActorRef();
        actor.setId("801"); //$NON-NLS-1$
        actor.setWidth(DRAGGED_OUT_WIDTH);
        actor.setHeight(DRAGGED_OUT_HEIGHT);
        graph.getContRefs().add(actor);

        for (int i = 0; i < 3; i++) {
            grl.IntentionalElementRef ref = grl.GrlFactory.eINSTANCE.createIntentionalElementRef();
            ref.setId("81" + i); //$NON-NLS-1$
            graph.getNodes().add(ref);
            actor.getNodes().add(ref);
        }
        return graph;
    }

    /** The width and height Graphviz gives the whole drawing, in inches. */
    private static double[] graphBox(String dot) throws Exception {
        Process p = new ProcessBuilder(AutoLayoutPreferences.locateDot(), "-Tplain") //$NON-NLS-1$
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        p.getOutputStream().write(dot.getBytes());
        p.getOutputStream().close();

        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        String line;
        double[] box = { 0, 0 };
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("graph ")) { //$NON-NLS-1$
                String[] parts = line.split("\\s+"); //$NON-NLS-1$
                box[0] = Double.parseDouble(parts[2]);
                box[1] = Double.parseDouble(parts[3]);
            }
        }
        reader.close();
        return box;
    }
}
