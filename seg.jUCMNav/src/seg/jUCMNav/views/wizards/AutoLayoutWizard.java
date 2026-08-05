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
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.NodeEditPart;
import org.eclipse.gef.commands.Command;
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
import seg.jUCMNav.model.commands.transformations.AutoLayoutCommand;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintBoundContainerRefCompoundCommand;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintCommand;
import seg.jUCMNav.model.commands.changeConstraints.SetConstraintContainerRefCommand;
import seg.jUCMNav.model.util.AutoLayoutCommandComparator;
import seg.jUCMNav.model.util.ChainPlacement;
import seg.jUCMNav.model.util.ComponentSeparation;
import seg.jUCMNav.model.util.ConstrainedPlacement;
import seg.jUCMNav.model.util.LabelExtent;
import seg.jUCMNav.model.util.LayeredLaneLayout;
import seg.jUCMNav.model.util.MetadataHelper;
import seg.jUCMNav.model.util.PathDetour;
import seg.jUCMNav.model.util.SwimlaneBands;
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

    /** Assumed extent of a node whose figure size was never recorded -- a UCM path node is small. */
    private static final int DEFAULT_NODE_EXTENT = 30;

    /** Fewest interior nodes a chain needs before a detour can be spread evenly across it. */
    private static final int DETOUR_MIN_NODES = 4;

    public AutoLayoutWizard(UrnEditor editor, IURNDiagram map) {
        this.diagram = map;
        this.editor = editor;
        AutoLayoutPreferences.createPreferences();
    }

    public void addPages() {
        addPage(new AutoLayoutDotSettingsWizardPage(Messages.getString("AutoLayoutWizard.dotConfig"), hasNonUcmDiagrams())); //$NON-NLS-1$
    }

    /** Whether this model holds a GRL graph or feature diagram, which only Graphviz can lay out. */
    private boolean hasNonUcmDiagrams() {
        if (diagram == null || diagram.getUrndefinition() == null)
            return !(diagram instanceof UCMmap) && diagram != null;

        for (Iterator<?> it = diagram.getUrndefinition().getSpecDiagrams().iterator(); it.hasNext();)
            if (!(it.next() instanceof UCMmap))
                return true;
        return false;
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
        final List<IURNDiagram> laidOut = new ArrayList<IURNDiagram>();
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
                            if (cmd != null && !cmd.isEmpty() && cmd.canExecute()) {
                                commands.add(cmd);
                                laidOut.add(target);
                            }

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
            //
            // One command, so one undo. Laying out a whole model used to execute one command per
            // diagram, and putting it back took one Ctrl+Z per diagram -- with every intermediate
            // press leaving some diagrams laid out and the rest not, which is a state the user
            // never asked for. A single diagram keeps its plain compound command on the page stack,
            // where its undo is not at the mercy of edits elsewhere; only a genuinely multi-diagram
            // layout needs the global stack. See AutoLayoutCommand.
            editor.execute(compose(commands, laidOut, diagram));

        } catch (Exception e) {
            Status status = new Status(IStatus.ERROR, "seg.jUCMNav", 1, e.toString(), e); //$NON-NLS-1$
            ErrorDialog.openError(getShell(), Messages.getString("AutoLayoutWizard.autoLayoutError"), //$NON-NLS-1$
                    Messages.getString("AutoLayoutWizard.repositioningError"), status, IStatus.ERROR | IStatus.WARNING); //$NON-NLS-1$
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * The one command the whole layout is undone by.
     *
     * <p>
     * A single diagram stays an ordinary {@code CompoundCommand} on its own page's stack: that is
     * already one undo, and a page stack keeps it until the user undoes it. Wrapping it as a global
     * command instead would park it on the URN-spec stack, where the next ordinary edit can discard
     * it -- a real loss for the common case, bought for nothing.
     *
     * <p>
     * Several diagrams cannot be owned by any one page's stack, so they become an
     * {@link AutoLayoutCommand}, which names the diagrams it touched so its undo survives edits
     * anywhere else.
     */
    public static Command compose(List<CompoundCommand> commands, List<IURNDiagram> laidOut, IURNDiagram primary) {
        if (commands.size() == 1)
            return commands.get(0);

        AutoLayoutCommand all = new AutoLayoutCommand(Messages.getString("AutoLayoutWizard.layingOut"), primary); //$NON-NLS-1$
        for (int i = 0; i < commands.size(); i++)
            all.add(laidOut.get(i), commands.get(i));
        return all;
    }

    // ------------------------------------------------------------- UCM: the contracted layout

    private CompoundCommand layoutUcm(UCMmap map) throws Exception {
        // Layered swim lanes by default: no Graphviz, no solver, and the URN containment rules hold
        // by construction rather than by repair. See LayeredLaneLayout, and issue #30 for the two
        // approaches it replaces -- both still reachable, so the three can be compared on real
        // models before anything is deleted.
        // The system property is a test and debugging override; the preference is what users set.
        boolean graphviz = "true".equals(System.getProperty("jucmnav.layout.graphviz")) //$NON-NLS-1$ //$NON-NLS-2$
                || AutoLayoutPreferences.ENGINE_GRAPHVIZ.equals(AutoLayoutPreferences.getEngine());
        if (!graphviz)
            return commandsFor(map, placeUcmLayered(map));

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
    /**
     * Where every node of a UCM map ends up under the default layout: layered swim lanes.
     *
     * Public and separate from {@link #layoutUcm} so the render sweep and the OCL legality oracle
     * can exercise exactly what the wizard does. They call this rather than reproducing it -- a
     * test that builds the layout its own way stops testing the layout the moment the two drift,
     * and this pair of oracles is the only thing standing between a layout change and the user.
     */
    public static Map<IURNNode, Point> placeUcmLayered(UCMmap map) {
        Map<IURNNode, Point> present = new HashMap<IURNNode, Point>();
        for (Iterator<?> it = map.getNodes().iterator(); it.hasNext();)
            present.put((IURNNode) it.next(), new Point(0, 0));

        return LayeredLaneLayout.layout(map, visualExtentsOf(present));
    }

    public static Map<IURNNode, Point> placeUcm(UcmPathDecomposition decomposition, PlainLayout layout) {
        Map<IURNNode, Point> positions = new HashMap<IURNNode, Point>();

        for (Iterator<PathNode> it = decomposition.getJunctions().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            PlainLayout.Node placed = layout.getNode(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) pn).getId());
            if (placed != null)
                positions.put(pn, toDiagram(placed.x, placed.y, layout));
        }

        // One constrained placement, rather than four passes each undoing part of the last. Only
        // the junctions are solved for -- every component member is one, and a chain's interior is
        // derived from the run between its two junctions -- so this is a fifteen-point problem.
        // See ConstrainedPlacement, and issue #30 for what it replaces.
        if (!"true".equals(System.getProperty("jucmnav.layout.passes"))) { //$NON-NLS-1$ //$NON-NLS-2$
            ConstrainedPlacement.solve(decomposition, positions, visualExtentsOf(positions), COMPONENT_MARGIN,
                    !"TB".equalsIgnoreCase(AutoLayoutPreferences.getOrientation())); //$NON-NLS-1$
            return ConstrainedPlacement.placeChainInteriors(decomposition, positions);
        }

        // The four-pass pipeline, kept behind a flag for one release so the two can be compared on
        // real models rather than on the sample alone. It goes when the sweep says it should.
        // Components are separated before the chains are routed, not after, so the routes can be
        // taken round the boxes in their final places. Only the junctions exist at this point,
        // which is all a component's rectangle is made of anyway.
        if (!"true".equals(System.getProperty("jucmnav.layout.noseparation"))) ComponentSeparation.apply(positions, extentsOf(positions), COMPONENT_MARGIN);
        Set<IURNNode> pulled = pullShapeNodesToTheirNeighbours(decomposition, positions);
        Map<Object, Rectangle> boxes = componentBoxes(positions);

        for (Iterator<UcmPathDecomposition.Chain> it = decomposition.getChains().iterator(); it.hasNext();) {
            UcmPathDecomposition.Chain chain = it.next();
            if (chain.length() == 0)
                continue;

            Point from = positions.get(chain.getFrom());
            Point to = positions.get(chain.getTo());
            if (from == null || to == null)
                continue;

            // A chain belongs to no component -- its interior is empty points and plain nodes --
            // so any component box it crosses is one it has no business being inside. Route round
            // rather than move the component: the bend points are free, and moving a component
            // disturbs everything already placed around it.
            // Only a chain with nodes to spare can absorb a detour. Bending a two-node chain round
            // a component stretches one gap and squeezes the other, and an interpolating spline
            // through unevenly spaced points overshoots -- the very thing the placement exists to
            // avoid. Measured: spacing ratio 7.1 against a bound of 3.
            List<Rectangle> obstacles = chain.length() >= DETOUR_MIN_NODES
                    ? new ArrayList<Rectangle>(boxes.values()) : new ArrayList<Rectangle>();
            boolean stale = pulled.contains(chain.getFrom()) || pulled.contains(chain.getTo());
            PointList route = stale ? straight(from, to) : routeOf(chain, layout, from, to);
            PointList spread = ChainPlacement.distribute(detour(route, obstacles), chain.length());
            List<PathNode> interior = chain.getInterior();
            for (int i = 0; i < interior.size() && i < spread.size(); i++)
                positions.put(interior.get(i), spread.getPoint(i));
        }

        // Graphviz decided the horizontal order, which is the topology. The vertical position is
        // reassigned so each component becomes a band of its own: position is semantics in URN, and
        // a layered layout has no notion of "a node must not sit inside a component that does not
        // perform it". See SwimlaneBands.
        if ("true".equals(System.getProperty("jucmnav.layout.swimlanes", "false")))
            return SwimlaneBands.apply(positions);
        return positions;
    }



    /**
     * Moves forks, joins, empty points and arrows to sit among the nodes they connect.
     *
     * <p>
     * Their binding means nothing -- they mark where a path branches or bends, not who does the
     * work -- so they may lie inside a component or outside it freely. Graphviz, knowing nothing of
     * that, places them by rank alone, which routinely leaves an AND-fork sitting outside the very
     * component holding both of its branches. The path then has to dive out of the component to
     * reach the fork and dive back in, once per branch, which is what the plunges are.
     *
     * <p>
     * Put the fork at the centre of what it connects and both dives disappear: the branches spread
     * from a point already among them. Nothing else has to move, and nothing is asserted that was
     * not true before, since the node's containment carries no meaning either way.
     */
    private static Set<IURNNode> pullShapeNodesToTheirNeighbours(UcmPathDecomposition decomposition, Map<IURNNode, Point> positions) {
        Set<IURNNode> moved = new java.util.HashSet<IURNNode>();
        for (Iterator<PathNode> it = decomposition.getJunctions().iterator(); it.hasNext();) {
            PathNode pn = it.next();
            if (!positions.containsKey(pn) || ComponentSeparation.bindingIsMeaningful(pn))
                continue;

            int sumX = 0, sumY = 0, count = 0;
            for (Iterator<UcmPathDecomposition.Chain> c = decomposition.getChains().iterator(); c.hasNext();) {
                UcmPathDecomposition.Chain chain = c.next();
                IURNNode other = chain.getFrom() == pn ? chain.getTo() : (chain.getTo() == pn ? chain.getFrom() : null);
                Point at = other == null ? null : positions.get(other);
                if (at == null)
                    continue;

                sumX += at.x;
                sumY += at.y;
                count++;
            }

            // Only the cross-axis is taken. The rank axis -- x under rankdir=LR -- is the order
            // the path runs in, and moving a fork along it can place the node behind its own
            // neighbours: the chain then doubles back, and an interpolating spline through a
            // reversal is the worst case there is. Measured at a 158-degree turn before this was
            // constrained. Across the flow there is no such ordering to break, so the fork drops
            // freely into the band its branches occupy.
            if (count >= 2) {
                Point at = positions.get(pn);
                boolean leftToRight = "LR".equals(AutoLayoutPreferences.getOrientation()) || "RL".equals(AutoLayoutPreferences.getOrientation());
                positions.put(pn, leftToRight ? new Point(at.x, sumY / count) : new Point(sumX / count, at.y));
                moved.add(pn);
            }
        }
        return moved;
    }

    /**
     * The straight line between two junctions.
     *
     * Used when either end has been moved since Graphviz routed the chain. Its spline was computed
     * for the old positions, and pinning the ends to the new ones while keeping the old middle puts
     * a spike in the curve exactly where the node moved -- measured at a 141-degree turn. A straight
     * line is a worse route and a much better shape, and ChainPlacement smooths it either way.
     */
    private static PointList straight(Point from, Point to) {
        PointList route = new PointList();
        route.addPoint(from);
        route.addPoint(to);
        return route;
    }

    /** The rectangle each outermost component occupies, given where its nodes are. */
    private static Map<Object, Rectangle> componentBoxes(Map<IURNNode, Point> positions) {
        Map<IURNNode, Dimension> extents = extentsOf(positions);
        Map<Object, Rectangle> boxes = new HashMap<Object, Rectangle>();

        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            IURNContainerRef ref = node.getContRef();
            if (ref == null)
                continue;
            while (ref.getParent() != null)
                ref = ref.getParent();

            Point at = positions.get(node);
            Dimension size = extents.get(node);
            int halfWidth = size == null ? 0 : size.width / 2;
            int halfHeight = size == null ? 0 : size.height / 2;
            Rectangle here = new Rectangle(at.x - halfWidth - COMPONENT_MARGIN, at.y - halfHeight - COMPONENT_MARGIN,
                    2 * (halfWidth + COMPONENT_MARGIN), 2 * (halfHeight + COMPONENT_MARGIN));

            Rectangle grown = boxes.get(ref);
            boxes.put(ref, grown == null ? here : grown.union(here));
        }
        return boxes;
    }

    /** Inserts bend waypoints wherever a route would cross a component it does not belong to. */
    private static PointList detour(PointList route, List<Rectangle> obstacles) {
        if (route.size() < 2 || obstacles.isEmpty())
            return route;

        PointList detoured = new PointList();
        detoured.addPoint(route.getPoint(0));

        for (int i = 1; i < route.size(); i++) {
            Point a = route.getPoint(i - 1);
            Point b = route.getPoint(i);

            PointList waypoints = PathDetour.around(a, b, obstacles);
            for (int w = 0; w < waypoints.size(); w++)
                detoured.addPoint(waypoints.getPoint(w));

            detoured.addPoint(b);
        }
        return detoured;
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

    /**
     * Slides the whole drawing up against the top-left corner.
     *
     * <p>
     * Fixes the acres of empty space a laid-out diagram could open above and to the left of itself
     * before anything was visible. The canvas grows to hold the origin, so content that starts at
     * x=1500 shows 1500px of nothing first, and content at a negative coordinate is worse.
     *
     * <p>
     * There were two ways to get there and this covers both. Graphviz coordinates are flipped
     * through {@code layout.getHeight()}, which is the whole graph's bounding box -- and that box
     * is inflated by the invisible {@code CheapTrick} placeholder {@code ExportLayoutDOT} emits per
     * container, sized from the container's <i>current</i> on-screen bounds. A 1681px actor becomes
     * a 23-inch node, so the real content lands far from the origin. Separately, anything that
     * translates whole components after placement can leave the drawing anywhere at all, including
     * at negative coordinates.
     *
     * <p>
     * A translation only: every node moves by the same offset, so no layout is altered by this --
     * which is what makes it safe to apply to GRL and feature diagrams, whose layout is otherwise
     * untouched.
     */
    private static void toOrigin(Map<IURNNode, Point> positions, Map<IURNNode, Dimension> extents) {
        if (positions == null || positions.isEmpty())
            return;

        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Point at = positions.get(node);
            Dimension size = extents == null ? null : extents.get(node);
            left = Math.min(left, at.x - (size == null ? 0 : size.width / 2));
            top = Math.min(top, at.y - (size == null ? 0 : size.height / 2));
        }
        if (left == Integer.MAX_VALUE)
            return;

        // Clear of the origin by a component's margin as well as the page padding, so a container
        // rectangle drawn around the outermost nodes still lands on the canvas.
        int dx = PADDING + COMPONENT_MARGIN - left;
        int dy = PADDING + COMPONENT_MARGIN - top;
        if (dx == 0 && dy == 0)
            return;

        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            Point at = positions.get(node);
            positions.put(node, new Point(at.x + dx, at.y + dy));
        }
    }

    /** Graphviz measures from the bottom left in points; the diagram measures from the top left. */
    private static Point toDiagram(double x, double y, PlainLayout layout) {
        return new Point((int) Math.round(x) + PADDING, (int) Math.round(layout.getHeight() - y) + PADDING);
    }

    /**
     * How big each node is drawn, from the dimensions harvested off the figures.
     *
     * A container's rectangle has to hold its nodes' <i>extents</i>, not their centres. A GRL
     * intentional element is drawn around 150x85; a box taken from centres alone is far too small,
     * and the actor then does not visually contain the elements bound to it.
     */
    /**
     * Node extents including their labels -- what actually occupies room in the drawing.
     *
     * A label is part of what an element takes up, and the placement has to keep labels off each
     * other and off the path just as much as figures. Erring generous costs a little whitespace;
     * erring mean costs a collision.
     */
    private static Map<IURNNode, Dimension> visualExtentsOf(Map<IURNNode, Point> positions) {
        Map<IURNNode, Dimension> sizes = extentsOf(positions);
        for (Iterator<Map.Entry<IURNNode, Dimension>> it = sizes.entrySet().iterator(); it.hasNext();) {
            Map.Entry<IURNNode, Dimension> entry = it.next();
            entry.setValue(LabelExtent.including((URNmodelElement) entry.getKey(), entry.getValue()));
        }
        return sizes;
    }

    private static Map<IURNNode, Dimension> extentsOf(Map<IURNNode, Point> positions) {
        Map<IURNNode, Dimension> sizes = new HashMap<IURNNode, Dimension>();

        for (Iterator<IURNNode> it = positions.keySet().iterator(); it.hasNext();) {
            IURNNode node = it.next();
            int width = DEFAULT_NODE_EXTENT, height = DEFAULT_NODE_EXTENT;

            String w = MetadataHelper.getMetaData((URNmodelElement) node, "_width"); //$NON-NLS-1$
            String h = MetadataHelper.getMetaData((URNmodelElement) node, "_height"); //$NON-NLS-1$
            try {
                if (w != null && h != null && Double.parseDouble(w) > 0 && Double.parseDouble(h) > 0) {
                    width = (int) Double.parseDouble(w);
                    height = (int) Double.parseDouble(h);
                }
            } catch (NumberFormatException keepTheDefault) {
                // metadata written by an older version
            }
            sizes.put(node, new Dimension(width, height));
        }
        return sizes;
    }

    public static CompoundCommand commandsFor(IURNDiagram diagram, Map<IURNNode, Point> positions) {
        Map<IURNNode, Dimension> extents = extentsOf(positions);

        // Placed freely by topology, then the boxes those placements imply are pushed apart. URN
        // only requires that containers not overlap and that nothing unbound be drawn inside one --
        // it does not require a container's members to occupy adjacent ranks, which is the extra
        // constraint a Graphviz cluster adds and the reason clusters tangled the path.
        if (!"true".equals(System.getProperty("jucmnav.layout.noseparation"))) ComponentSeparation.apply(positions, extents, COMPONENT_MARGIN);

        toOrigin(positions, extents);

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
