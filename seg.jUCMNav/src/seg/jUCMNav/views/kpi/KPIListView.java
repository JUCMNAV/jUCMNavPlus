package seg.jUCMNav.views.kpi;

import grl.Evaluation;
import grl.EvaluationStrategy;
import grl.GRLspec;
import grl.StrategiesGroup;
import grl.kpimodel.Indicator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.gef.ContextMenuProvider;
import org.eclipse.gef.editparts.AbstractTreeEditPart;
import org.eclipse.gef.ui.parts.TreeViewer;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.ViewPart;

import seg.jUCMNav.JUCMNavPlugin;
import seg.jUCMNav.Messages;
import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.editors.UrnEditDomain;
import seg.jUCMNav.editors.actionContributors.KPIListViewContextMenuProvider;
import seg.jUCMNav.editparts.kpiTreeEditparts.IndicatorTreeEditPart;
import seg.jUCMNav.editparts.kpiTreeEditparts.KPIRootEditPart;
import seg.jUCMNav.editparts.kpiTreeEditparts.KPITreeEditPartFactory;
import seg.jUCMNav.strategies.EvaluationStrategyManager;
import seg.jUCMNav.views.JUCMNavRefreshableView;
import seg.jUCMNav.views.preferences.DisplayPreferences;

/**
 * 
 * The KPI list view.
 * 
 * @author pchen
 * 
 */
public class KPIListView extends ViewPart implements IPartListener2, ISelectionChangedListener, JUCMNavRefreshableView {
    private TreeViewer viewer;

    private UCMNavMultiPageEditor multieditor;
    private Indicator currentIndicator;
    private IndicatorTreeEditPart currentSelection;

    private IAction showNodeNumberAction;

    /**
     * The constructor.
     */
    public KPIListView() {
    }

    /**
     * This is a callback that will allow us to create the viewer and initialize it.
     */
    public void createPartControl(Composite parent) {
        viewer = new TreeViewer();
        viewer.addSelectionChangedListener(this);

        viewer.setEditDomain(new UrnEditDomain(null));

        getSite().getPage().addPartListener(this);

        viewer.createControl(parent);
        getSite().setSelectionProvider(viewer);

        // The "Retrieve KPI Values" toolbar action was removed with the KPI web-service
        // feature (issue #1). It called a SOAP/JAX-RPC service via Apache Axis 1.x;
        // JAX-RPC was deleted from the JDK and has no Jakarta successor, so the
        // implementation went away during the Java 21 migration and the button had been
        // left doing nothing (its failure was swallowed to System.out). Retired rather
        // than re-implemented -- there is no monitoring backend to talk to.

        DisplayPreferences.getInstance().registerListener(this);

        // Add the showNodeNumber action.
        showNodeNumberAction = new Action() {
            public void run() {
                DisplayPreferences.getInstance().setShowNodeNumber(showNodeNumberAction.isChecked());
            }
        };

        showNodeNumberAction.setImageDescriptor(JUCMNavPlugin.getImageDescriptor("icons/identifiers.png")); //$NON-NLS-1$
        showNodeNumberAction.setToolTipText(Messages.getString("UrnOutlinePage.ShowElementsIds")); //$NON-NLS-1$ 
        showNodeNumberAction.setText(Messages.getString("UrnOutlinePage.ShowElementsIds")); //$NON-NLS-1$ 
        showNodeNumberAction.setChecked(DisplayPreferences.getInstance().getShowNodeNumber());

        IToolBarManager tbm = getViewSite().getActionBars().getToolBarManager();
        tbm.add(showNodeNumberAction);
    }

    /**
     * Removes listeners
     * 
     * @see org.eclipse.ui.part.IPage#dispose()
     */
    public void dispose() {
        if (multieditor != null) {
            // unhook outline viewer
            // multieditor.getSelectionSynchronizer().removeViewer(viewer);
        }

        Object p = viewer.getRootEditPart();
        if (p instanceof AbstractTreeEditPart) {
            ((AbstractTreeEditPart) p).setModel(null);
        }

        if (viewer.getContextMenu() != null) {
            viewer.getContextMenu().dispose();
            viewer.setContextMenu(null);
        }

        if (viewer.getEditDomain() instanceof UrnEditDomain) {
            UrnEditDomain domain = (UrnEditDomain) viewer.getEditDomain();
            domain.dispose();
        }

        DisplayPreferences.getInstance().unregisterListener(this);
        getSite().getPage().removePartListener(this);

        // dispose
        super.dispose();

        viewer = null;
        multieditor = null;
        currentIndicator = null;
        currentSelection = null;
        showNodeNumberAction = null;
    }

    /**
     * Passing the focus request to the viewer's control.
     */
    public void setFocus() {
        if (!isViewerAlive())
            return;
        if (viewer.getContents() != null) {
            viewer.getControl().setFocus();
        }
    }

    /**
     * True when the viewer still has a live SWT control behind it.
     *
     * dispose() nulls viewer, and on workbench close the platform disposes the
     * control BEFORE delivering the final partClosed/partActivated events, so a
     * plain {@code viewer != null} check is not enough. In particular GEF's
     * RootTreeEditPart.setContents() calls {@code ((Tree) getWidget()).removeAll()}
     * guarded only by a null check, so viewer.setContents(null) on a disposed tree
     * raises "SWTException: Widget is disposed".
     */
    private boolean isViewerAlive() {
        return viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partActivated(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partActivated(IWorkbenchPartReference partRef) {
        if (partRef.getPart(false) == this || partRef.getPart(false) instanceof UCMNavMultiPageEditor) {
            setEditor(partRef);
        } else {
            // bug 709 - if we are no longer selecting a UCM editor, flush the current selection.
            if (!(partRef.getPage().getActiveEditor() instanceof UCMNavMultiPageEditor)) {
                setEditor((UCMNavMultiPageEditor) null);
                if (isViewerAlive())
                    viewer.setContents(null);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partBroughtToTop(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partBroughtToTop(IWorkbenchPartReference partRef) {
        // If the part brought to top is this view, then update the input.
        if (partRef.getPart(false) == this) {
            setEditor(partRef);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partClosed(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partClosed(IWorkbenchPartReference partRef) {
        if (isViewerAlive() && partRef.getPart(false) instanceof UCMNavMultiPageEditor && partRef.getPage().getActiveEditor() == null) {
            viewer.setContents(null);
        }

        currentIndicator = null;
        currentSelection = null;

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partDeactivated(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partDeactivated(IWorkbenchPartReference partRef) {
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partOpened(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partOpened(IWorkbenchPartReference partRef) {
        if (partRef.getPart(false) instanceof UCMNavMultiPageEditor || partRef.getPart(false) == this)
            setEditor(partRef);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partHidden(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partHidden(IWorkbenchPartReference partRef) {
        // viewer.setContents(null);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partVisible(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partVisible(IWorkbenchPartReference partRef) {
        if (partRef.getPart(false) instanceof UCMNavMultiPageEditor || partRef.getPart(false) == this) {
            setEditor(partRef);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.IPartListener2#partInputChanged(org.eclipse.ui.IWorkbenchPartReference)
     */
    public void partInputChanged(IWorkbenchPartReference partRef) {
    }

    private void setEditor(IWorkbenchPartReference partRef) {
        if (partRef.getPage().getActiveEditor() instanceof UCMNavMultiPageEditor) {
            setEditor((UCMNavMultiPageEditor) partRef.getPage().getActiveEditor());
        }
    }

    /**
     * @param editor
     */
    private void setEditor(UCMNavMultiPageEditor editor) {
        if (multieditor != editor) {
            multieditor = editor;
            if (multieditor == null) {
                return;
            }

            multieditor.getCurrentPage().getGraphicalViewer().addSelectionChangedListener(this);

            if (viewer.getEditDomain() instanceof UrnEditDomain) {
                ((UrnEditDomain) viewer.getEditDomain()).dispose();
            }
            viewer.setEditDomain(new UrnEditDomain(multieditor));
            viewer.setEditPartFactory(new KPITreeEditPartFactory(multieditor.getModel()));

            // register them. other ways failed to add undo/redo, only added delete.
            IActionBars bars = getViewSite().getActionBars();
            String id = ActionFactory.UNDO.getId();
            bars.setGlobalActionHandler(id, multieditor.getActionRegistry().getAction(id));
            id = ActionFactory.REDO.getId();
            bars.setGlobalActionHandler(id, multieditor.getActionRegistry().getAction(id));
            id = ActionFactory.DELETE.getId();
            bars.setGlobalActionHandler(id, multieditor.getActionRegistry().getAction(id));

            // Hook context menu
            ContextMenuProvider cmProvider = new KPIListViewContextMenuProvider(viewer, multieditor.getActionRegistry());
            viewer.setContextMenu(cmProvider);
            getSite().registerContextMenu("seg.jUCMNav.editors.actionContributors.KPIContextMenuProvider", cmProvider, getSite().getSelectionProvider()); //$NON-NLS-1$

            // hook viewer
            // if (editor != null)
            // editor.getSelectionSynchronizer().removeViewer(viewer);
            // multieditor.getSelectionSynchronizer().addViewer(viewer);
            viewer.setContents(multieditor);

            Tree tree = (Tree) viewer.getControl();
            if (tree.getTopItem() != null) { // fix for crash on linux!
                Object[] items = tree.getTopItem().getItems();
                for (int i = 0; i < items.length; i++) {
                    ((TreeItem) items[i]).setExpanded(true);
                }
                tree.getTopItem().setExpanded(true);
            }
        }
        EvaluationStrategyManager.getInstance().setKPIListViewer(viewer);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jface.viewers.ISelectionChangedListener#selectionChanged(org.eclipse.jface.viewers.SelectionChangedEvent)
     */
    public void selectionChanged(SelectionChangedEvent event) {
        StructuredSelection sel = (StructuredSelection) event.getSelection();

        if ((event.getSource() instanceof TreeViewer) && (multieditor != null)) {
            for (Iterator j = sel.iterator(); j.hasNext();) {
                Object obj = j.next();

                if (obj instanceof IndicatorTreeEditPart) {
                    if (currentSelection != null) {
                        currentSelection.setSelected(false);
                    }
                    currentSelection = (IndicatorTreeEditPart) obj;

                    Indicator cind = ((IndicatorTreeEditPart) obj).getIndicator();
                    currentIndicator = cind;
                }

            }
        }
    }

    public Object getAdapter(Class adapter) {
        if (adapter == org.eclipse.ui.views.properties.IPropertySheetPage.class) {
            if (multieditor != null)
                return multieditor.getAdapter(org.eclipse.ui.views.properties.IPropertySheetPage.class);
            else
                return super.getAdapter(adapter);

        } else {
            return super.getAdapter(adapter);
        }
    }

    public void refreshView() {
        viewer.setContents(viewer.getContents());
        showNodeNumberAction.setChecked(DisplayPreferences.getInstance().getShowNodeNumber());
    }
}