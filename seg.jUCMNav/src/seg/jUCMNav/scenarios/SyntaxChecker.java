package seg.jUCMNav.scenarios;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import seg.jUCMNav.JUCMNavPlugin;
import seg.jUCMNav.Messages;
import seg.jUCMNav.editors.UCMNavMultiPageEditor;
import seg.jUCMNav.editors.resourceManagement.UrnModelManager;
import seg.jUCMNav.model.util.URNNamingHelper;
import seg.jUCMNav.scenarios.model.TraversalWarning;
import seg.jUCMNav.scenarios.parser.SimpleNode;
import ucm.map.EndPoint;
import ucm.map.FailurePoint;
import ucm.map.NodeConnection;
import ucm.map.OrFork;
import ucm.map.PathNode;
import ucm.map.PluginBinding;
import ucm.map.StartPoint;
import ucm.map.UCMmap;
import ucm.map.WaitingPlace;
import ucm.scenario.ScenarioDef;
import ucm.scenario.ScenarioGroup;
import urn.URNspec;
import urncore.Condition;
import urncore.IURNDiagram;
import urncore.Responsibility;
import urncore.URNmodelElement;

/**
 * Verifies the syntax of all conditions / responsibilities. Also manages refreshing the problems view.
 * 
 * @author jkealey
 * 
 */
public class SyntaxChecker {

    /**
     * Verifies a condition's syntax.
     * 
     * @param urn
     *            the urnspec
     * @param errors
     *            where should errors be appended.
     * @param location
     *            the location where the condition is valuated.. to produce useful traversal warnings.
     * @param expr
     *            the condition's expression.
     * 
     */
    private static void verifyCondition(URNspec urn, Vector<TraversalWarning> errors, EObject location, String expr) {
        Object o = ScenarioUtils.parse(expr, ScenarioUtils.getEnvironment(urn), false);
        if (!(o instanceof SimpleNode)) {
            TraversalWarning warning = new TraversalWarning((String) o, location, IMarker.SEVERITY_ERROR);
            warning.setExpression(expr);
            errors.add(warning);
        }
    }

    /**
     * Verifies all conditions associated to path nodes or node connections in all maps.
     * 
     * @param urn
     *            the urnspec
     * @param errors
     *            where should errors be appended.
     */
    private static void verifyMapConditions(URNspec urn, Vector<TraversalWarning> errors) {
        for (Iterator iter = urn.getUrndef().getSpecDiagrams().iterator(); iter.hasNext();) {
            IURNDiagram diag = (IURNDiagram) iter.next();
            if (diag instanceof UCMmap) {
                for (Iterator iterator = ((UCMmap) diag).getNodes().iterator(); iterator.hasNext();) {
                    PathNode node = (PathNode) iterator.next();
                    if (node instanceof StartPoint) {
                        if (((StartPoint) node).getPrecondition() != null) {
                            verifyCondition(urn, errors, node, ((StartPoint) node).getPrecondition().getExpression());
                        }
                    } else if (node instanceof EndPoint) {
                        if (((EndPoint) node).getPostcondition() != null) {
                            verifyCondition(urn, errors, node, ((EndPoint) node).getPostcondition().getExpression());
                        }
                    } else if (node instanceof OrFork || node instanceof WaitingPlace || node instanceof FailurePoint) {
                        for (Iterator it2 = node.getSucc().iterator(); it2.hasNext();) {
                            NodeConnection nc = (NodeConnection) it2.next();
                            if (nc.getCondition() != null) {
                                verifyCondition(urn, errors, node, nc.getCondition().getExpression());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifies the syntax of every plugin binding in every map.
     * 
     * @param urn
     *            the urnspec
     * @param errors
     *            where should errors be appended.
     */
    private static void verifyPluginBindingSyntax(URNspec urn, Vector<TraversalWarning> errors) {
        for (Iterator iter = urn.getUrndef().getSpecDiagrams().iterator(); iter.hasNext();) {
            IURNDiagram diag = (IURNDiagram) iter.next();
            if (diag instanceof UCMmap) {
                for (Iterator iterator = ((UCMmap) diag).getParentStub().iterator(); iterator.hasNext();) {
                    PluginBinding binding = (PluginBinding) iterator.next();
                    if (binding.getPrecondition() != null) {
                        String expr = binding.getPrecondition().getExpression();
                        verifyCondition(urn, errors, binding.getStub(), expr);
                    }
                }
            }
        }
    }

    /**
     * Verify the code associated to all responsibilities
     * 
     * @param urn
     *            the urnspec
     * @param errors
     *            where should errors be appended.
     */
    private static void verifyResponsibilitySyntax(URNspec urn, Vector<TraversalWarning> errors) {
        for (Iterator iter = urn.getUrndef().getResponsibilities().iterator(); iter.hasNext();) {
            Responsibility resp = (Responsibility) iter.next();
            if (!ScenarioUtils.isEmptyResponsibility(resp)) {
                Object o = ScenarioUtils.parse(resp.getExpression(), ScenarioUtils.getEnvironment(resp), true);
                if (!(o instanceof SimpleNode)) {
                    if (resp.getRespRefs().size() > 0)
                        errors.add(new TraversalWarning((String) o, (EObject) resp.getRespRefs().get(0), IMarker.SEVERITY_ERROR));
                    else
                        errors.add(new TraversalWarning((String) o, resp, IMarker.SEVERITY_ERROR));

                    errors.get(errors.size() - 1).setExpression(resp.getExpression());
                }
            }
        }
    }

    /**
     * Verify the code associated to all failure poitns
     * 
     * @param urn
     *            the urnspec
     * @param errors
     *            where should errors be appended.
     */
    private static void verifyFailurePointSyntax(URNspec urn, Vector<TraversalWarning> errors) {
        for (Iterator iter = urn.getUrndef().getSpecDiagrams().iterator(); iter.hasNext();) {
            IURNDiagram diag = (IURNDiagram) iter.next();
            if (diag instanceof UCMmap) {
                for (Iterator iterator = diag.getNodes().iterator(); iterator.hasNext();) {
                    PathNode pn = (PathNode) iterator.next();
                    if (pn instanceof FailurePoint) {
                        FailurePoint fail = (FailurePoint) pn;
                        Object o = ScenarioUtils.parse(fail.getExpression(), ScenarioUtils.getEnvironment(fail), true);
                        if (!(o instanceof SimpleNode)) {
                            errors.add(new TraversalWarning((String) o, fail, IMarker.SEVERITY_ERROR));
                            errors.get(errors.size() - 1).setExpression(fail.getExpression());
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifies the syntax of all scenario pre/post conditions.
     * 
     * @param urn
     *            the urnspec
     * @param errors
     *            where should errors be appended.
     */
    private static void verifyScenarioPrePostConditions(URNspec urn, Vector<TraversalWarning> errors) {
        for (Iterator iter = urn.getUcmspec().getScenarioGroups().iterator(); iter.hasNext();) {
            ScenarioGroup group = (ScenarioGroup) iter.next();
            for (Iterator iterator = group.getScenarios().iterator(); iterator.hasNext();) {
                ScenarioDef scenario = (ScenarioDef) iterator.next();
                for (Iterator it2 = scenario.getPreconditions().iterator(); it2.hasNext();) {
                    Condition cond = (Condition) it2.next();
                    verifyCondition(urn, errors, scenario, cond.getExpression());
                }
                for (Iterator it2 = scenario.getPostconditions().iterator(); it2.hasNext();) {
                    Condition cond = (Condition) it2.next();
                    verifyCondition(urn, errors, scenario, cond.getExpression());
                }
            }
        }
    }

    /**
     * Returns a vector of TraversalWarnings for all the elements that do not have a valid syntax.
     * 
     * @param urn
     *            the urnspec to be analyzed
     * @return vector of TraversalWarnings for all the elements that do not have a valid syntax.
     */
    public static Vector<TraversalWarning> verifySyntax(URNspec urn) {
        Vector<TraversalWarning> errors = new Vector<TraversalWarning>();
        verifyResponsibilitySyntax(urn, errors);
        verifyFailurePointSyntax(urn, errors);
        verifyPluginBindingSyntax(urn, errors);
        verifyMapConditions(urn, errors);
        verifyScenarioPrePostConditions(urn, errors);
        if (JUCMNavPlugin.isInDebug())
            verifyUniqueIDs(urn, errors);
        return errors;

    }

    public static void verifyUniqueIDs(URNspec urn, Vector<TraversalWarning> errors) {
        UrnModelManager manager = new UrnModelManager();
        try {
            Vector duplicates = manager.getDuplicateIDs(urn);

            for (Iterator iterator = duplicates.iterator(); iterator.hasNext();) {
                URNmodelElement o = (URNmodelElement) iterator.next();
                errors.add(new TraversalWarning(Messages.getString("SyntaxChecker_ElementAsADuplicateID") + o.getId(), o, IMarker.SEVERITY_ERROR)); //$NON-NLS-1$

            }
        } catch (IOException ex) {
            errors.add(new TraversalWarning(Messages.getString("SyntaxChecker_UnableToCheckForDuplicateIDs") + ex.getMessage(), IMarker.SEVERITY_ERROR)); //$NON-NLS-1$
        }

    }

    /**
     * Clears the warnings associated to this file and replaces them with those supplied in the vector.
     * 
     * @param warnings
     *            a vector of {@link TraversalWarning}s to be pushed to the problems view.
     */
    public static void refreshProblemsView(final Vector warnings) {
        org.eclipse.ui.IWorkbenchWindow win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        org.eclipse.ui.IWorkbenchPage page = win != null ? win.getActivePage() : null;
        org.eclipse.ui.IEditorPart activeEditor = page != null ? page.getActiveEditor() : null;
        if (activeEditor instanceof UCMNavMultiPageEditor) {
            UCMNavMultiPageEditor editor = (UCMNavMultiPageEditor) activeEditor;
            final IFile resource = ((FileEditorInput) editor.getEditorInput()).getFile();

            // Every marker call is a workspace operation that broadcasts a resource delta on
            // its own, and this used to issue one delete per existing marker plus up to eight
            // setAttribute calls per new one. Running them inside a single IWorkspaceRunnable
            // collapses that to one delta, which matters on models that produce thousands of
            // traversal warnings. See also UCMNavMultiPageEditor.dispose(), which clears these
            // markers the same way.
            IWorkspaceRunnable batch = new IWorkspaceRunnable() {
                public void run(IProgressMonitor monitor) throws CoreException {
                    writeMarkers(resource, warnings);
                }
            };

            try {
                // null rule on purpose: marker changes are exempt from scheduling rules
                // (IResourceRuleFactory.markerRule() returns null), so this cannot conflict
                // with a rule an outer operation already holds.
                ResourcesPlugin.getWorkspace().run(batch, null, IWorkspace.AVOID_UPDATE, null);
            } catch (CoreException ex) {
                System.out.println(ex);
            }
        }
    }

    /**
     * Replaces the traversal markers on the file. Must be called inside an
     * {@link IWorkspaceRunnable}; see {@link #refreshProblemsView(Vector)}.
     *
     * @param resource
     *            the file the markers belong to
     * @param warnings
     *            the {@link TraversalWarning}s to publish
     */
    private static void writeMarkers(IFile resource, Vector warnings) throws CoreException {
        // bulk delete; one operation instead of one per existing marker
        resource.deleteMarkers("seg.jUCMNav.traverseproblem", true, 3); //$NON-NLS-1$

        for (Iterator iter = warnings.iterator(); iter.hasNext();) {
            TraversalWarning o = (TraversalWarning) iter.next();

            try {
                // Collect the attributes first and set them in one call: IMarker.setAttribute()
                // is a workspace operation each time, setAttributes() is a single one.
                Map<String, Object> attribs = new HashMap<String, Object>();
                attribs.put(IMarker.SEVERITY, Integer.valueOf(o.getSeverity()));
                attribs.put(IMarker.MESSAGE, o.toString());
                if (o.getLocation() instanceof URNmodelElement) {
                    URNmodelElement elem = (URNmodelElement) o.getLocation();
                    attribs.put(IMarker.LOCATION, URNNamingHelper.getName(elem));
                    attribs.put("EObject", ((URNmodelElement) o.getLocation()).getId()); //$NON-NLS-1$
                } else if (o.getLocation() != null) {
                    attribs.put(IMarker.LOCATION, o.getLocation().toString());
                }

                if (o.getCondition() != null && o.getCondition().eContainer() != null) {
                    if (o.getCondition().eContainer() instanceof StartPoint) {
                        StartPoint start = (StartPoint) o.getCondition().eContainer();
                        attribs.put("NodePreCondition", start.getId()); //$NON-NLS-1$
                    } else if (o.getCondition().eContainer() instanceof EndPoint) {
                        EndPoint end = (EndPoint) o.getCondition().eContainer();
                        attribs.put("NodePostCondition", end.getId()); //$NON-NLS-1$
                    } else if (o.getCondition().eContainer() instanceof NodeConnection) {
                        NodeConnection ncx = (NodeConnection) o.getCondition().eContainer();
                        PathNode pn = (PathNode) ncx.getSource();
                        attribs.put("Condition", pn.getId()); //$NON-NLS-1$
                        for (int i = 0; i < pn.getSucc().size(); i++) {
                            NodeConnection nc = (NodeConnection) pn.getSucc().get(i);
                            if (nc.getCondition() == o.getCondition()) {
                                attribs.put("ConditionIndex", Integer.valueOf(i)); //$NON-NLS-1$
                            }
                        }
                    } else if (o.getCondition().eContainer() instanceof ScenarioDef) {
                        ScenarioDef scenario = (ScenarioDef) o.getCondition().eContainer();
                        attribs.put("Scenario", scenario.getId()); //$NON-NLS-1$
                        attribs.put("ScenarioPreConditionIndex", Integer.valueOf(scenario.getPreconditions().indexOf(o.getCondition()))); //$NON-NLS-1$
                        attribs.put("ScenarioPostConditionIndex", Integer.valueOf(scenario.getPostconditions().indexOf(o.getCondition()))); //$NON-NLS-1$
                    }
                } else if (o.getLocation() instanceof OrFork || o.getLocation() instanceof WaitingPlace || o.getLocation() instanceof FailurePoint) {
                    PathNode pn = (PathNode) o.getLocation();
                    attribs.put("Condition", pn.getId()); //$NON-NLS-1$
                }

                IMarker marker = resource.createMarker("seg.jUCMNav.traverseproblem"); //$NON-NLS-1$
                marker.setAttributes(attribs);
            } catch (CoreException ex) {
                // System.out.println(ex);
            }
        }
    }

}
