package seg.jUCMNav.views.wizards;

import java.io.File;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

import seg.jUCMNav.Messages;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;

/**
 * Settings page for the autolayout wizard.
 * 
 * @author jkealey
 * 
 */
public class AutoLayoutDotSettingsWizardPage extends WizardPage {
    private Combo cboOrientation, cboEngine;

    private Label lblOrientation, lblScope;

    private Text txtDotPath;

    private Button chkAllDiagrams;

    /**
     * Whether the model holds anything that Graphviz still has to draw.
     *
     * Conservative on purpose: any GRL graph or feature diagram anywhere in the model counts, not
     * just the ones about to be laid out, because the "all diagrams" box can be ticked after this
     * is decided. Leaving the orientation control live when it might matter is the safe error.
     */
    private final boolean hasNonUcmTargets;

    /**
     * @param pageName
     */
    protected AutoLayoutDotSettingsWizardPage(String pageName, boolean hasNonUcmTargets) {
        super(pageName);
        this.hasNonUcmTargets = hasNonUcmTargets;
        setDescription(Messages.getString("AutoLayoutDotSettingsWizardPage.pleaseEnterPreferences")); //$NON-NLS-1$
        setTitle(Messages.getString("AutoLayoutDotSettingsWizardPage.autoLayoutWizard")); //$NON-NLS-1$

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jface.dialogs.IDialogPage#createControl(org.eclipse.swt.widgets .Composite)
     */
    public void createControl(Composite parent) {

        PlatformUI.getWorkbench().getHelpSystem().setHelp(parent, "seg.jUCMNav.autolayout"); //$NON-NLS-1$

        // create the composite to hold the widgets
        Composite composite = new Composite(parent, SWT.NONE);

        // create the desired layout for this wizard page
        GridLayout gl = new GridLayout(4, false);
        composite.setLayout(gl);
        GridData data;

        Label lblPath = new Label(composite, SWT.NONE);
        lblPath.setText(Messages.getString("AutoLayoutDotSettingsWizardPage.dotPath")); //$NON-NLS-1$
        data = new GridData();
        data.horizontalSpan = 4;
        lblPath.setLayoutData(data);

        txtDotPath = new Text(composite, SWT.BORDER | SWT.SINGLE | SWT.LEFT);
        setDotPath(AutoLayoutPreferences.getDotPath());

        data = new GridData();
        data.horizontalAlignment = GridData.FILL;
        data.grabExcessHorizontalSpace = true;
        data.horizontalSpan = 3;
        // data.grabExcessVerticalSpace = true;
        txtDotPath.setLayoutData(data);
        txtDotPath.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
            }

            public void focusLost(FocusEvent e) {
                setDotPath(txtDotPath.getText());
            }
        });

        Button b = new Button(getShell(), SWT.PUSH);
        b.setParent(composite);
        b.setText("..."); //$NON-NLS-1$
        b.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
                dialog.setFileName(AutoLayoutPreferences.getDotPath());
                dialog.setText(Messages.getString("AutoLayoutDotSettingsWizardPage.selectGraphvizDot")); //$NON-NLS-1$
                String path = dialog.open();

                if (path != null)
                    setDotPath(path);

            }
        });

        Label lblEngine = new Label(composite, SWT.NONE);
        lblEngine.setText(Messages.getString("AutoLayoutDotSettingsWizardPage.engine")); //$NON-NLS-1$
        data = new GridData();
        data.horizontalSpan = 2;
        lblEngine.setLayoutData(data);

        cboEngine = new Combo(composite, SWT.READ_ONLY);
        cboEngine.setItems(new String[] { Messages.getString("AutoLayoutDotSettingsWizardPage.engineLayered"), //$NON-NLS-1$
                Messages.getString("AutoLayoutDotSettingsWizardPage.engineGraphviz") }); //$NON-NLS-1$
        cboEngine.select(AutoLayoutPreferences.ENGINE_GRAPHVIZ.equals(AutoLayoutPreferences.getEngine()) ? 1 : 0);
        data = new GridData();
        data.horizontalAlignment = GridData.FILL;
        data.horizontalSpan = 2;
        cboEngine.setLayoutData(data);
        cboEngine.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                AutoLayoutPreferences.setEngine(cboEngine.getSelectionIndex() == 1 ? AutoLayoutPreferences.ENGINE_GRAPHVIZ
                        : AutoLayoutPreferences.ENGINE_LAYERED);
                // Whether Graphviz is required depends on this choice, so the page's verdict on a
                // missing dot has to be recomputed the moment it changes.
                refreshGraphvizStatus();
                refreshOrientationEnablement();
            }
        });

        lblScope = new Label(composite, SWT.WRAP);
        lblScope.setText(Messages.getString("AutoLayoutDotSettingsWizardPage.engineScope")); //$NON-NLS-1$
        data = new GridData(GridData.FILL_HORIZONTAL);
        data.horizontalSpan = 4;
        data.widthHint = 420;
        lblScope.setLayoutData(data);

        lblOrientation = new Label(composite, SWT.NONE);
        lblOrientation.setText(Messages.getString("AutoLayoutDotSettingsWizardPage.orientation")); //$NON-NLS-1$
        data = new GridData();
        data.horizontalSpan = 2;
        lblOrientation.setLayoutData(data);

        cboOrientation = new Combo(composite, SWT.READ_ONLY);
        cboOrientation.setItems(new String[] {
                Messages.getString("AutoLayoutDotSettingsWizardPage.topdown"), Messages.getString("AutoLayoutDotSettingsWizardPage.leftright") }); //$NON-NLS-1$ //$NON-NLS-2$

        if (AutoLayoutPreferences.getOrientation().equalsIgnoreCase("TB")) //$NON-NLS-1$
            cboOrientation.select(0); // TB
        else
            cboOrientation.select(1); // LR

        data = new GridData();
        data.horizontalAlignment = GridData.FILL;
        data.horizontalSpan = 2;
        cboOrientation.setLayoutData(data);

        cboOrientation.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
            }

            public void focusLost(FocusEvent e) {
                setOrientation(cboOrientation.getSelectionIndex());
            }
        });

        chkAllDiagrams = new Button(composite, SWT.CHECK);
        chkAllDiagrams.setText(Messages.getString("AutoLayoutDotSettingsWizardPage.allDiagrams")); //$NON-NLS-1$
        chkAllDiagrams.setSelection(AutoLayoutPreferences.getAllDiagrams());
        data = new GridData();
        data.horizontalSpan = 4;
        chkAllDiagrams.setLayoutData(data);
        chkAllDiagrams.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
            }

            public void focusLost(FocusEvent e) {
                AutoLayoutPreferences.setAllDiagrams(chkAllDiagrams.getSelection());
            }
        });

        refreshOrientationEnablement();
        setControl(composite);

    }

    /**
     * Orientation only matters where Graphviz is what draws the diagram.
     *
     * <p>
     * It sets {@code rankdir}, so it governs the Graphviz engine -- and it governs GRL graphs and
     * feature diagrams too, which go through Graphviz whatever engine is chosen for UCM maps. It
     * does nothing at all to the layered layout, whose flow axis is its own.
     *
     * <p>
     * So this is greyed out for the layered engine, but only when the model has nothing in it that
     * still needs Graphviz. Disabling it whenever "layered" is picked would take away the only
     * control over how a user's GRL diagrams are turned.
     */
    private void refreshOrientationEnablement() {
        boolean matters = AutoLayoutPreferences.ENGINE_GRAPHVIZ.equals(AutoLayoutPreferences.getEngine()) || hasNonUcmTargets;
        cboOrientation.setEnabled(matters);
        lblOrientation.setEnabled(matters);
    }

    public void setDotPath(String path) {
        txtDotPath.setText(path);
        AutoLayoutPreferences.setDotPath(path);
        refreshGraphvizStatus();
    }

    /**
     * Complains about a missing Graphviz only when one is actually needed.
     *
     * <p>
     * A missing dot used to make the page incomplete, which disabled Finish. That was right when
     * every layout went through Graphviz. It is wrong now: the layered layout does not run dot at
     * all, so blocking on its absence would stop a user laying out a UCM map for the sake of a
     * dependency that layout has no use for.
     *
     * <p>
     * It stays a hard block when the Graphviz engine is chosen, since that one genuinely cannot
     * proceed. GRL graphs and feature diagrams still need dot whatever is chosen here -- they have
     * no layered layout yet -- so their absence is reported as a warning rather than silently, and
     * they fail with the usual error if the user goes ahead without one.
     */
    private void refreshGraphvizStatus() {
        if (doesDotPathExist()) {
            setErrorMessage(null);
            setMessage(null);
            setPageComplete(true);
            return;
        }

        String missing = Messages.getString("AutoLayoutDotSettingsWizardPage.GraphvizNotFound"); //$NON-NLS-1$
        boolean required = AutoLayoutPreferences.ENGINE_GRAPHVIZ.equals(AutoLayoutPreferences.getEngine());

        setErrorMessage(required ? missing : null);
        setMessage(required ? null : Messages.getString("AutoLayoutDotSettingsWizardPage.GraphvizOnlyForGrl"), WARNING); //$NON-NLS-1$
        setPageComplete(!required);
    }


    public void setOrientation(int i) {
        cboOrientation.select(i);
        if (i == 0)
            AutoLayoutPreferences.setOrientation("TB"); //$NON-NLS-1$
        else
            AutoLayoutPreferences.setOrientation("LR"); //$NON-NLS-1$

    }




    private boolean doesDotPathExist() {
        try {
            File f = new File(AutoLayoutPreferences.getDotPath());
            return f.exists();
        } catch (Exception ex) {
            return false;
        }
    }

}