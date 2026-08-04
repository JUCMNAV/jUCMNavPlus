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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.NodeEditPart;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
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
            ProcessBuilder builder = new ProcessBuilder(new String[] { dot, "-T" + output_format }); //$NON-NLS-1$

            // Discard stderr rather than leave it unread. dot warns readily -- a label that will
            // not fit is one warning per node -- and an unread pipe fills at around 64KB, after
            // which dot blocks writing to it and never produces output. That is not a theoretical
            // deadlock: it is what made auto-layout hang for minutes on a large model with nothing
            // to show and nothing to cancel. The labels that caused it are gone too, but the
            // process must not be able to wedge us again whatever dot decides to complain about.
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);

            final Process p = builder.start();

            // Feed stdin from another thread. dot buffers the whole graph before laying it out, so
            // in practice it drains us first, but a writer that blocks while we are not yet
            // reading is the same class of deadlock and costs one thread to rule out.
            final byte[] input = input_for_dot;
            Thread feeder = new Thread(new Runnable() {
                public void run() {
                    try {
                        OutputStream ostream = p.getOutputStream();
                        ostream.write(input);
                        ostream.close();
                    } catch (IOException ignored) {
                        // dot exited early; the empty output is reported by the caller
                    }
                }
            }, "graphviz-stdin"); //$NON-NLS-1$
            feeder.setDaemon(true);
            feeder.start();

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

    /**
     * The diagrams this run will lay out: just the one being edited, or every diagram in the model
     * when the wizard's "all diagrams" box is ticked. One is the default, because laying out a
     * whole model is a large, surprising change to make by accident.
     */
    private List<IURNDiagram> targets() {
        List<IURNDiagram> targets = new ArrayList<IURNDiagram>();

        if (!AutoLayoutPreferences.getAllDiagrams() || diagram == null || diagram.getUrndefinition() == null) {
            targets.add(diagram);
            return targets;
        }

        for (Iterator<?> it = diagram.getUrndefinition().getSpecDiagrams().iterator(); it.hasNext();)
            targets.add((IURNDiagram) it.next());
        return targets;
    }

    public boolean performFinish() {
        final List<IURNDiagram> targets = targets();
        final List<CompoundCommand> commands = new ArrayList<CompoundCommand>();
        final Exception[] failure = new Exception[1];

        try {
            // Runs on the UI thread (fork = false) so nothing touches the model or the editparts
            // off it, but cancellable and reporting progress, which is what a run over a whole
            // model needs -- and what the old one lacked when a large map made it look hung.
            getContainer().run(false, true, new IRunnableWithProgress() {
                public void run(IProgressMonitor monitor) {
                    monitor.beginTask(Messages.getString("AutoLayoutWizard.layingOut"), targets.size()); //$NON-NLS-1$
                    try {
                        for (Iterator<IURNDiagram> it = targets.iterator(); it.hasNext();) {
                            if (monitor.isCanceled())
                                return;

                            IURNDiagram target = it.next();
                            monitor.subTask(name(target));

                            CompoundCommand cmd = target instanceof UCMmap ? layoutUcm((UCMmap) target) : layoutGeneric(target);
                            if (cmd != null && !cmd.isEmpty() && cmd.canExecute())
                                commands.add(cmd);

                            monitor.worked(1);
                        }
                    } catch (Exception e) {
                        failure[0] = e;
                    } finally {
                        monitor.done();
                    }
                }
            });
        } catch (Exception e) {
            failure[0] = e;
        }

        try {
            if (failure[0] != null)
                throw failure[0];

            if (commands.isEmpty()) {
                MessageDialog.openWarning(getShell(), Messages.getString("AutoLayoutWizard.autoLayoutError"), //$NON-NLS-1$
                        Messages.getString("AutoLayoutWizard.nothingToPosition")); //$NON-NLS-1$
                return false;
            }

            // Executed after the progress dialog closes, so a cancel leaves the model untouched
            // rather than half laid out.
            for (Iterator<CompoundCommand> it = commands.iterator(); it.hasNext();)
                editor.execute(it.next());

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

        return commandsFor(map, placeUcm(decomposition, new PlainLayout(plain)));
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

    private CompoundCommand layoutGeneric(IURNDiagram target) throws Exception {
        addIntentionalElemRefDimensions();

        String plain = autoLayoutDotString(ExportLayoutDOT.convertURNToDot(target));
        if (plain.length() == 0)
            return null;

        return repositionLayout(target, plain);
    }

    /** A diagram's name, for the progress dialog. */
    private static String name(IURNDiagram target) {
        String named = target instanceof URNmodelElement ? ((URNmodelElement) target).getName() : null;
        return named == null || named.length() == 0 ? String.valueOf(target) : named;
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

        return commandsFor(urndiagram, positions);
    }

    // ------------------------------------------------------------------------------- shared

    /** Graphviz measures from the bottom left in points; the diagram measures from the top left. */
    private static Point toDiagram(double x, double y, PlainLayout layout) {
        return new Point((int) Math.round(x) + PADDING, (int) Math.round(layout.getHeight() - y) + PADDING);
    }

    public static CompoundCommand commandsFor(IURNDiagram diagram, Map<IURNNode, Point> positions) {
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

    /**
     * Records how big each element is actually drawn, so Graphviz lays out around the real figures.
     *
     * <p>
     * Reads the editor for <b>this</b> diagram rather than whichever page the workbench happens to
     * have in front. It used to ask {@code PlatformUI} for the active editor's current page, which
     * is right only when the diagram being laid out is the one on screen. Importing a model lays
     * out every page in turn ({@code jUCMNavLoader}), so for all but one of them the sizes came
     * from the wrong diagram -- and where nothing matched, elements were declared 0 x 0 and packed
     * at a spacing their figures do not fit in.
     */
    public void addIntentionalElemRefDimensions() {
        if (editor == null || editor.getGraphicalViewer() == null)
            return;

        Collection<EditPart> editparts = editor.getGraphicalViewer().getEditPartRegistry().values();

        for (EditPart editPart : new ArrayList<EditPart>(editparts)) {
            if (!(editPart instanceof NodeEditPart) || !(editPart instanceof IntentionalElementEditPart))
                continue;

            NodeEditPart nodeEditPart = (NodeEditPart) editPart;
            if (nodeEditPart.getFigure() == null || !(nodeEditPart.getModel() instanceof IURNNode))
                continue;

            int height = nodeEditPart.getFigure().getBounds().height;
            int width = nodeEditPart.getFigure().getBounds().width;
            if (height <= 0 || width <= 0)
                continue; // never recorded: a zero size is worse than the default

            IURNNode node = (IURNNode) nodeEditPart.getModel();
            MetadataHelper.addMetaData(node.getDiagram().getUrndefinition().getUrnspec(), (URNmodelElement) node, "_height", String.valueOf(height)); //$NON-NLS-1$
            MetadataHelper.addMetaData(node.getDiagram().getUrndefinition().getUrnspec(), (URNmodelElement) node, "_width", String.valueOf(width)); //$NON-NLS-1$
        }
    }
}
