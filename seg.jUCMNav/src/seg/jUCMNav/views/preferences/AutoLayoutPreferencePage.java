package seg.jUCMNav.views.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import seg.jUCMNav.JUCMNavPlugin;
import seg.jUCMNav.Messages;

/**
 * Preference page for the autolayout mechanism
 * 
 * @author jkealey
 * 
 */
public class AutoLayoutPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public AutoLayoutPreferencePage() {
        super(FieldEditorPreferencePage.GRID);

        // Set the preference store for the preference page.
        IPreferenceStore store = JUCMNavPlugin.getDefault().getPreferenceStore();
        setPreferenceStore(store);

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jface.preference.FieldEditorPreferencePage#createFieldEditors()
     */
    protected void createFieldEditors() {
        // Initialize all field editors.
        FileFieldEditor dotPath = new FileFieldEditor(AutoLayoutPreferences.PREF_DOTPATH, Messages.getString("AutoLayoutDotSettingsWizardPage.dotPath"), //$NON-NLS-1$
                getFieldEditorParent());
        addField(dotPath);

        String[][] values = { { Messages.getString("AutoLayoutDotSettingsWizardPage.topdown"), "TB" }, //$NON-NLS-1$ //$NON-NLS-2$
                { Messages.getString("AutoLayoutDotSettingsWizardPage.leftright"), "LR" } }; //$NON-NLS-1$ //$NON-NLS-2$

        RadioGroupFieldEditor orientation = new RadioGroupFieldEditor(AutoLayoutPreferences.PREF_ORIENTATION, Messages
                .getString("AutoLayoutDotSettingsWizardPage.orientation"), 1, values, getFieldEditorParent()); //$NON-NLS-1$
        addField(orientation);
    }

    public void init(IWorkbench workbench) {

    }

    public boolean isValid() {
        return true; // otherwise we get stuck in the preference pages.
    }

}
