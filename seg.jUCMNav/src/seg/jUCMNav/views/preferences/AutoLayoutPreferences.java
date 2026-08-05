package seg.jUCMNav.views.preferences;

import org.eclipse.jface.preference.IPreferenceStore;

import seg.jUCMNav.JUCMNavPlugin;

/**
 * Encapsulates load/save of the autolayout preferences.
 * 
 * @author jkealey
 * 
 */
public class AutoLayoutPreferences {

	public final static String DEFAULTDOTPATH = "c:\\program files\\ATT\\GraphViz\\bin\\dot.exe"; //$NON-NLS-1$
	public final static String DEFAULTNONWINDOWSDOTPATH = ""; //$NON-NLS-1$
	/**
	 * Left to right, because that is how a use case map is drawn: a path runs across the page from
	 * its start point to its end point. Top-to-bottom was the old default and turns the same model
	 * into a column. Rendered side by side on the reporter's model, LR is the difference between a
	 * readable flow and a tangle.
	 */
	public final static String DEFAULTORIENTATION = "LR"; //$NON-NLS-1$

	/** Layered swim lanes: no Graphviz, and the URN containment rules hold by construction. */
	public final static String ENGINE_LAYERED = "layered"; //$NON-NLS-1$

	/** The Graphviz-seeded constrained placement. Kept for comparison on real models. */
	public final static String ENGINE_GRAPHVIZ = "graphviz"; //$NON-NLS-1$
	/** Lay out only the diagram you are looking at, unless asked otherwise. */
	public final static boolean DEFAULTALLDIAGRAMS = false;
	public final static String PREF_DOTPATH = "seg.jUCMNav.AutoLayout.DotPath"; //$NON-NLS-1$
	public final static String PREF_ORIENTATION = "seg.jUCMNav.AutoLayout.Orientation"; //$NON-NLS-1$
	public final static String PREF_ENGINE = "seg.jUCMNav.AutoLayout.Engine"; //$NON-NLS-1$
	public final static String PREF_ALLDIAGRAMS = "seg.jUCMNav.AutoLayout.AllDiagrams"; //$NON-NLS-1$
	public final static String URNODEPREFIX = "UrnNode"; //$NON-NLS-1$
	public final static String DIAGPREFIX = "UrnDiag"; //$NON-NLS-1$
	// must start with cluster if we want them rendered.
	public final static String CONTAINERPREFIX = "cluster_ContainerRef"; //$NON-NLS-1$

	/**
	 * 
	 * @return Preference store where the properties are stored.
	 */
	public static IPreferenceStore getPreferenceStore() {
		return JUCMNavPlugin.getDefault().getPreferenceStore();
	}

	/**
	 * Sets the default values in the preference store.
	 */
	public static void createPreferences() {
		if (System.getProperty("os.name").startsWith("Windows")) //$NON-NLS-1$ //$NON-NLS-2$
		{
			// Default only provided to Windows. See bug #561
			getPreferenceStore().setDefault(AutoLayoutPreferences.PREF_DOTPATH, AutoLayoutPreferences.DEFAULTDOTPATH);
		} else {
			getPreferenceStore().setDefault(AutoLayoutPreferences.PREF_DOTPATH, AutoLayoutPreferences.DEFAULTNONWINDOWSDOTPATH);
		}

		getPreferenceStore().setDefault(AutoLayoutPreferences.PREF_ORIENTATION, AutoLayoutPreferences.DEFAULTORIENTATION);
		getPreferenceStore().setDefault(AutoLayoutPreferences.PREF_ENGINE, AutoLayoutPreferences.ENGINE_LAYERED);
		getPreferenceStore().setDefault(AutoLayoutPreferences.PREF_ALLDIAGRAMS, AutoLayoutPreferences.DEFAULTALLDIAGRAMS);
	}

	/**
	 * 
	 * @return the path where Graphviz dot is located
	 */
	/**
	 * The dot executable to use, or null when Graphviz cannot be found.
	 *
	 * The preference wins when it points at something that exists. Otherwise the usual install
	 * locations are tried, then bare "dot" on the PATH -- the shipped default is an AT&T-era path
	 * that has not existed for well over a decade, so for most users the preference is worse than
	 * no answer at all.
	 */
	public static String locateDot() {
		String preferred = getDotPath();
		if (preferred != null && preferred.length() > 0 && new java.io.File(preferred).canExecute())
			return preferred;

		String[] candidates = { "C:\\Program Files\\Graphviz\\bin\\dot.exe", //$NON-NLS-1$
				"C:\\Program Files (x86)\\Graphviz\\bin\\dot.exe", //$NON-NLS-1$
				"/usr/bin/dot", "/usr/local/bin/dot", "/opt/homebrew/bin/dot" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		for (int i = 0; i < candidates.length; i++) {
			if (new java.io.File(candidates[i]).canExecute())
				return candidates[i];
		}

		// Last resort: let the OS resolve it. Costs one failed process launch when absent.
		try {
			Process p = Runtime.getRuntime().exec(new String[] { "dot", "-V" }); //$NON-NLS-1$ //$NON-NLS-2$
			if (p.waitFor() == 0)
				return "dot"; //$NON-NLS-1$
		} catch (Exception ignored) {
			// not on the PATH either
		}
		return null;
	}

	public static String getDotPath() {
		return getPreferenceStore().getString(PREF_DOTPATH);
	}

	/**
	 * 
	 * @return the height parameter to give dot
	 */

	/**
	 * 
	 * @return the orientation (TB, LR)
	 */
/**
	 * Which layout to use for UCM maps.
	 *
	 * <p>
	 * Only UCM maps have a choice: GRL graphs and feature diagrams are laid out by
	 * {@code ExportLayoutDOT} through Graphviz whatever this says, because the layered layout is
	 * written against UCM paths and components and has no counterpart for them yet.
	 */
	public static String getEngine() {
		String engine = getPreferenceStore().getString(PREF_ENGINE);
		return ENGINE_GRAPHVIZ.equals(engine) ? ENGINE_GRAPHVIZ : ENGINE_LAYERED;
	}

	public static void setEngine(String engine) {
		getPreferenceStore().setValue(PREF_ENGINE, ENGINE_GRAPHVIZ.equals(engine) ? ENGINE_GRAPHVIZ : ENGINE_LAYERED);
	}

	/** Whether Graphviz has to be present for what the user is about to lay out. */
	public static boolean needsGraphviz(boolean hasNonUcmDiagrams) {
		return hasNonUcmDiagrams || ENGINE_GRAPHVIZ.equals(getEngine());
	}

	public static String getOrientation() {
		return getPreferenceStore().getString(PREF_ORIENTATION);
	}

	/**
	 * 
	 * @return the width parameter to give dot
	 */

	/**
	 * 
	 * @return should our empty points be manipulated during the transformation
	 */
	/** Whether to lay out every diagram in the model rather than just the current one. */
	public static boolean getAllDiagrams() {
		return getPreferenceStore().getBoolean(PREF_ALLDIAGRAMS);
	}

	public static void setAllDiagrams(boolean b) {
		getPreferenceStore().setValue(PREF_ALLDIAGRAMS, b);
	}


	/**
	 * 
	 * @param path
	 *            the path where Graphviz dot is located
	 */
	public static void setDotPath(String path) {
		getPreferenceStore().setValue(PREF_DOTPATH, path);
	}

	/**
	 * 
	 * @param height
	 *            the height parameter to give dot
	 */

	/**
	 * 
	 * @param str
	 *            the orientation (TB, LR)
	 */
	public static void setOrientation(String str) {
		getPreferenceStore().setValue(PREF_ORIENTATION, str);
	}

	/**
	 * 
	 * @param width
	 *            the width parameter to give dot
	 */

	/**
	 * 
	 * @param b
	 *            should our empty points be manipulated during the transformation
	 */

}
