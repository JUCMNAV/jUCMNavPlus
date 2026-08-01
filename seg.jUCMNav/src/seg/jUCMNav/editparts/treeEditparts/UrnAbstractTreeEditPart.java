package seg.jUCMNav.editparts.treeEditparts;

import org.eclipse.gef.editparts.AbstractTreeEditPart;

import seg.jUCMNav.model.util.URNNamingHelper;

/***
 * This class was added during some outline tests. It is the common base class of every jUCMNav
 * tree edit part, which makes it the one place that can normalize what those trees display.
 *
 * @author jkealey
 *
 */
public class UrnAbstractTreeEditPart extends AbstractTreeEditPart {
    public UrnAbstractTreeEditPart() {

    }

    public UrnAbstractTreeEditPart(Object model) {
        setModel(model);
    }

    /**
     * Shows multi-line names on the single line a tree item can render.
     *
     * A TreeItem draws only up to the first line break, so a responsibility or stub whose name
     * spans several lines showed just its first line -- and elements sharing that first line
     * became impossible to tell apart. Folding the breaks into spaces here covers every jUCMNav
     * tree at once: the hierarchical outline, the definitions and concerns outlines, and the
     * strategy, scenario, KPI and dynamic-context trees. Subclasses that override
     * refreshVisuals() all delegate to super, so none bypass this.
     *
     * This restates AbstractTreeEditPart#refreshVisuals() rather than wrapping it, because
     * setWidgetText() is final there and cannot be intercepted. The inherited body is exactly
     * these two calls.
     *
     * @see URNNamingHelper#getSingleLineName(String)
     */
    protected void refreshVisuals() {
        setWidgetImage(getImage());
        setWidgetText(URNNamingHelper.getSingleLineName(getText()));
    }
}
