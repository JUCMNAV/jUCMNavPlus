package seg.jUCMNav.views.wizards;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.NodeEditPart;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.PlatformUI;

import seg.jUCMNav.Messages;
import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.editors.UrnEditor;
import seg.jUCMNav.editparts.IntentionalElementEditPart;
import seg.jUCMNav.importexport.ExportContractedDOT;
import seg.jUCMNav.importexport.ExportLayoutDOT;
import seg.jUCMNav.importexport.PlainLayout;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintBoundContainerRefCompoundCommand;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintCommand;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintContainerRefCommand;
import seg.jUCMNav.model.util.AutoLayoutCommandComparator;
import seg.jUCMNav.model.util.ChainPlacement;
import seg.jUCMNav.model.util.MetadataHelper;
import seg.jUCMNav.model.util.UcmPathDecomposition;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import ucm.map.PathNode;
import ucm.map.UCMmap;
import urncore.IURNContainerRef;
import urncore.IURNDiagram;
import urncore.IURNNode;
import urncore.URNmodelElement;

/**
 * The autolayout wizard.
 *
 * <p>
 * Graphviz is asked for the topology and nothing else. For a UCM map it is not even shown the whole
 * map: {@link UcmPathDecomposition} contracts each run of pass-through nodes to a single edge, so
 * what goes over is the junctions -- forks, joins, stubs, path ends, and anything a component holds
 * -- plus the component clusters. A 200-node map becomes a fifteen-node problem, which is the size
 * Graphviz's crossing minimisation is actually good at.
 *
 * <p>
 * The interior of each chain is then placed here, by {@link ChainPlacement}, evenly along the route
 * Graphviz chose. That part has to be ours: a UCM path is drawn as an interpolating cubic spline
 * through its nodes, so its shape is decided by their spacing and turn angle, neither of which a
 * layered layout reasons about. Sampling Graphviz's own Bezier for bend points -- what this did
 * before -- interpolated one spline through points taken off another, which is why paths bulged and
 * looped.
 *
 * <p>
 * Graphviz is read through {@code -Tplain}, a documented line-oriented format, rather than by
 * scraping {@code -Tdot} with regexes pinned to releases from 2011. See #30.
 *
 * @author jkealey, Claude
 */
public class AutoLayoutWizard extends Wizard {

    private IURNDiagram diagram;
    private UrnEditor editor;
    public static final int PADDING = 50;

    /** Breathing room between a component's boundary and the nodes it holds. */
    private static final int COMPONENT_MARGIN = 30;

    public AutoLayoutWizard(UrnEditor editor, IURNDiagram map) {
        this.diagram = map;
        this.editor = editor;
        AutoLayoutPreferences.createPreferences();
    }

    public void addPages() {
        addPage(new AutoLayoutDotSettingsWizardPage(Messages.getString("AutoLayoutWizard.dotConfig"))); //$NON-NLS-1$
    }

    /**
     * Runs Graphviz over the given DOT source and returns its {@code -Tplain} output.
     *
     * The name is historical; callers outside this class pass the result to
     * {@link #repositionLayout(IURNDiagram, String)}, and the pair stay consistent.
     */
    public String autoLayoutDotString(String initial) {
        StringBuffer builder = new StringBuffer();
        InputStream is = callDOT(initial.getBytes(), "plain"); //$NON-NLS-1$
        if (is == null)
            return ""; //$NON-NLS-1$

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            String s;
            while ((s = reader.readLine()) != null)
                builder.append(s + "\n"); //$NON-NLS-1$
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return builder.toString();
    }

    private synchronized InputStream callDOT(byte input_for_dot[], String output_format) {
        InputStream istream = null;
        String dot = AutoLayoutPreferences.locateDot();
        if (dot == null) {
            MessageDialog.openError(getShell(), Messages.getString("AutoLayoutWizard.autoLayoutError"), //$NON-NLS-1$
                    Messages.getString("AutoLayoutWizard.graphvizNotFound")); //$NON-NLS-1$
            return null;
        }

        try {
            Process p = Runtime.getRuntime().exec(new String[] { dot, "-T" + output_format }); //$NON-NLS-1$
            OutputStream ostream = p.getOutputStream();
            ostream.write(input_for_dot);
            ostream.close();
            istream = new BufferedInputStream(p.getInputStream());
        } catch (Exception e) {
            Status status = new Status(IStatus.ERROR, "seg.jUCMNav", 1, e.toString(), e); //$NON-NLS-1$
            ErrorDialog.openError(getShell(), Messages.getString("AutoLayoutWizard.autoLayoutError"), //$NON-NLS-1$
                    Messages.getString("AutoLayoutWizard.errorOccured"), //$NON-NLS-1$
                    status, IStatus.ERROR | IStatus.WARNING);
            return null;
        }
        return istream;
    }

    public boolean performFinish() {
        try {
            CompoundCommand cmd = diagram instanceof UCMmap ? layoutUcm((UCMmap) diagram) : layoutGeneric();

            if (cmd == null)
                return false;

            if (cmd.isEmpty()) {
                MessageDialog.openWarning(getShell(), Messages.getString("AutoLayoutWizard.autoLayoutError"), //$NON-NLS-1$
                        Messages.getString("AutoLayoutWizard.nothingToPosition")); //$NON-NLS-1$
                return false;
            }

            if (cmd.canExecute())
                editor.execute(cmd);

        } catch (Exception e) {
            Status status = new Status(IStatus.ERROR, "seg.jUCMNav", 1, e.toString(), e); //$NON-NLS-1$
            ErrorDialog.openError(getShell(), Messages.getString("AutoLayoutWizard.autoLayoutError"), //$NON-NLS-1$
                    Messages.getString("AutoLayoutWizard.repositioningError"), status, IStatus.ERROR | IStatus.WARNING); //$NON-NLS-1$
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------- UCM: the contracted layout

    private CompoundCommand layoutUcm(UCMmap map) throws Exception {
        UcmPathDecomposition decomposition = new UcmPathDecomposition(map);

        String plain = autoLayoutDotString(ExportContractedDOT.convert(map, decomposition));
        if (plain.length() == 0)
            return null;

        return positionsToCommands(map, placeUcm(decomposition, new PlainLayout(plain)));
    }

    /**
     * Where every node of a UCM map ends up: junctions where Graphviz put them, chain interiors
     * spread along the route between.
     *
     * Separated from command building so it can be exercised on a real model without a workbench.
     */
    public static Map<IURNNode, Point> placeUcm(UcmPathDecomposition decomposition, PlainLayout layout) {
        Map<IURNNode, Point> positions = new HashMap<IURNNode, Point>();

        for (Iterator<PathNode> it = decomposition.getJunctions().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            PlainLayout.Node placed = layout.getNode(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) pn).getId());
            if (placed != null)
                positions.put(pn, toDiagram(placed.x, placed.y, layout));
        }

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            if (chain.length() == 0)
                continue;

            Point from = positions.get(chain.getFrom());
            Point to = positions.get(chain.getTo());
            if (from == null || to == null)
                continue;

            PointList spread = ChainPlacement.distribute(routeOf(chain, layout, from, to), chain.length());
            List<PathNode> interior = chain.getInterior();
            for (int i = 0; i < interior.size() && i < spread.size(); i++)
                positions.put(interior.get(i), spread.getPoint(i));
        }

        return positions;
    }

    /**
     * The polyline a chain follows: Graphviz's route for the contracted edge when it gave one,
     * otherwise the straight line between the two junctions.
     *
     * The ends are pinned to the junctions' own positions. Graphviz's spline stops at a node's
     * boundary rather than its centre, and a chain that stops short of its junction puts a kink in
     * the curve exactly where it shows most.
     */
    private static PointList routeOf(UcmPathDecomposition.Chain chain, PlainLayout layout, Point from, Point to) {
        PointList route = new PointList();
        route.addPoint(from);

        PlainLayout.Edge edge = layout.getEdge(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) chain.getFrom()).getId(),
                AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) chain.getTo()).getId());
        if (edge != null)
            for (int i = 0; i < edge.size(); i++)
                route.addPoint(toDiagram(edge.xs[i], edge.ys[i], layout));

        route.addPoint(to);
        return route;
    }

    // ------------------------------------------------------------------ GRL and anything else

    private CompoundCommand layoutGeneric() throws Exception {
        addIntentionalElemRefDimensions();

        String plain = autoLayoutDotString(ExportLayoutDOT.convertURNToDot(diagram));
        if (plain.length() == 0)
            return null;

        return repositionLayout(diagram, plain);
    }

    /**
     * Applies a {@code -Tplain} layout to every node of a diagram it can find one for.
     *
     * Kept public and keyed on the diagram because {@code ShowLinkedElementInNewDiagramCommand} and
     * the importers lay out a graph they have just built, without going through the wizard.
     */
    public static CompoundCommand repositionLayout(IURNDiagram urndiagram, String plain) throws Exception {
        PlainLayout layout = new PlainLayout(plain);
        Map<IURNNode, Point> positions = new HashMap<IURNNode, Point>();

        for (Iterator<?> it = urndiagram.getNodes().iterator(); it.hasNext();) {
            IURNNode node = (IURNNode) it.next();
            PlainLayout.Node placed = layout.getNode(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) node).getId());
            if (placed != null)
                positions.put(node, toDiagram(placed.x, placed.y, layout));
        }

        return positionsToCommands(urndiagram, positions);
    }

    // ------------------------------------------------------------------------------- shared

    /** Graphviz measures from the bottom left in points; the diagram measures from the top left. */
    private static Point toDiagram(double x, double y, PlainLayout layout) {
        return new Point((int) Math.round(x) + PADDING, (int) Math.round(layout.getHeight() - y) + PADDING);
    }

    private static CompoundCommand positionsToCommands(IURNDiagram diagram, Map<IURNNode, Point> positions) {
        CompoundCommand cmd = new CompoundCommand();

        for (Iterator<?> it = diagram.getContRefs().iterator(); it.hasNext();) {
            IURNContainerRef ref = (IURNContainerRef) it.next();
            if (ref.getParent() == null)
                resize(ref, positions, cmd);
        }

        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Point at = positions.get(node);
            cmd.add(new SetConstraintCommand(node, at.x, at.y));
        }

        // bug 304: container moves have to precede node moves.
        Collections.sort(cmd.getCommands(), new AutoLayoutCommandComparator());
        return cmd;
    }

    /**
     * A component's rectangle, taken from what it holds rather than from Graphviz.
     *
     * {@code -Tplain} does not report cluster boxes, and deriving them is better anyway: the model
     * requires containment to follow geometry, so a rectangle computed from the final positions of
     * the nodes inside it is right by construction, where one copied from a cluster box has to be
     * trusted to agree.
     *
     * @return the rectangle as {left, top, right, bottom}, or null when nothing inside was placed
     */
    private static int[] resize(IURNContainerRef ref, Map<IURNNode, Point> positions, CompoundCommand cmd) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        boolean any = false;

        for (Iterator<?> it = ref.getChildren().iterator(); it.hasNext();) {
            Object child = it.next();
            if (!(child instanceof IURNContainerRef))
                continue;

            int[] inner = resize((IURNContainerRef) child, positions, cmd);
            if (inner != null) {
                left = Math.min(left, inner[0]);
                top = Math.min(top, inner[1]);
                right = Math.max(right, inner[2]);
                bottom = Math.max(bottom, inner[3]);
                any = true;
            }
        }

        for (Iterator<?> it = ref.getNodes().iterator(); it.hasNext();) {
            Point at = positions.get(it.next());
            if (at == null)
                continue;

            left = Math.min(left, at.x);
            top = Math.min(top, at.y);
            right = Math.max(right, at.x);
            bottom = Math.max(bottom, at.y);
            any = true;
        }

        if (!any)
            return null;

        left -= COMPONENT_MARGIN;
        top -= COMPONENT_MARGIN;
        right += COMPONENT_MARGIN;
        bottom += COMPONENT_MARGIN;

        cmd.add(new SetConstraintBoundContainerRefCompoundCommand(ref, left, top, right - left, bottom - top));
        if (ref.getParent() != null)
            cmd.add(new SetConstraintContainerRefCommand(ref, left, top, right - left, bottom - top));

        return new int[] { left, top, right, bottom };
    }

    public void addIntentionalElemRefDimensions() {
        UCMNavMultiPageEditor multi = (UCMNavMultiPageEditor) PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
        Collection<EditPart> editparts = ((UrnEditor) multi.getCurrentPage()).getGraphicalViewer().getEditPartRegistry().values();

        for (EditPart editPart : new ArrayList<EditPart>(editparts)) {
            if (editPart instanceof NodeEditPart && editPart instanceof IntentionalElementEditPart) {
                NodeEditPart nodeEditPart = (NodeEditPart) editPart;
                int height = nodeEditPart.getFigure().getBounds().height;
                int width = nodeEditPart.getFigure().getBounds().width;

                IURNNode node = (IURNNode) nodeEditPart.getModel();
                MetadataHelper.addMetaData(node.getDiagram().getUrndefinition().getUrnspec(), (URNmodelElement) node, "_height", String.valueOf(height)); //$NON-NLS-1$
                MetadataHelper.addMetaData(node.getDiagram().getUrndefinition().getUrnspec(), (URNmodelElement) node, "_width", String.valueOf(width)); //$NON-NLS-1$
            }
        }
    }
}
