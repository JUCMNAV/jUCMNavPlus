package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.views.preferences.AutoLayoutPreferences;

/**
 * Which layout a user gets, and when Graphviz is actually required.
 *
 * <p>
 * One preference decides which engine every UCM map in the tool is laid out by, so it is worth
 * more than an eyeball. The Graphviz question matters just as much in the other direction: the
 * wizard used to refuse to finish without a working dot, which is now wrong for the layered layout
 * and still right for the other one.
 *
 * @author Claude
 */
public class AutoLayoutEngineChoiceTest {

    private String saved;

    @Before
    public void rememberPreference() {
        AutoLayoutPreferences.createPreferences();
        saved = AutoLayoutPreferences.getEngine();
    }

    @After
    public void restorePreference() {
        AutoLayoutPreferences.setEngine(saved);
    }

    /** Layered is what a user gets without choosing, which is the whole point of the port. */
    @Test
    public void layeredIsTheDefault() {
        AutoLayoutPreferences.getPreferenceStore().setToDefault(AutoLayoutPreferences.PREF_ENGINE);

        assertEquals(AutoLayoutPreferences.ENGINE_LAYERED, AutoLayoutPreferences.getEngine());
    }

    @Test
    public void theChoiceIsRemembered() {
        AutoLayoutPreferences.setEngine(AutoLayoutPreferences.ENGINE_GRAPHVIZ);
        assertEquals(AutoLayoutPreferences.ENGINE_GRAPHVIZ, AutoLayoutPreferences.getEngine());

        AutoLayoutPreferences.setEngine(AutoLayoutPreferences.ENGINE_LAYERED);
        assertEquals(AutoLayoutPreferences.ENGINE_LAYERED, AutoLayoutPreferences.getEngine());
    }

    /**
     * A workspace that predates this preference holds an empty string for it. Falling through to
     * the Graphviz engine there would quietly give existing users the layout being replaced.
     */
    @Test
    public void anUnsetOrUnknownValueMeansLayered() {
        AutoLayoutPreferences.getPreferenceStore().setValue(AutoLayoutPreferences.PREF_ENGINE, ""); //$NON-NLS-1$
        assertEquals(AutoLayoutPreferences.ENGINE_LAYERED, AutoLayoutPreferences.getEngine());

        AutoLayoutPreferences.getPreferenceStore().setValue(AutoLayoutPreferences.PREF_ENGINE, "sugiyama"); //$NON-NLS-1$
        assertEquals(AutoLayoutPreferences.ENGINE_LAYERED, AutoLayoutPreferences.getEngine());
    }

    /**
     * Graphviz is needed for the Graphviz engine, and for GRL and feature diagrams whatever the
     * engine -- those have no layered layout yet. It is <b>not</b> needed to lay out UCM maps with
     * the default, which is why the wizard no longer refuses to finish without one.
     */
    @Test
    public void graphvizIsRequiredOnlyWhenSomethingActuallyNeedsIt() {
        AutoLayoutPreferences.setEngine(AutoLayoutPreferences.ENGINE_LAYERED);
        assertTrue("UCM maps alone must not require Graphviz", !AutoLayoutPreferences.needsGraphviz(false)); //$NON-NLS-1$
        assertTrue("GRL and feature diagrams still do", AutoLayoutPreferences.needsGraphviz(true)); //$NON-NLS-1$

        AutoLayoutPreferences.setEngine(AutoLayoutPreferences.ENGINE_GRAPHVIZ);
        assertTrue("and so does the Graphviz engine", AutoLayoutPreferences.needsGraphviz(false)); //$NON-NLS-1$
    }
}
