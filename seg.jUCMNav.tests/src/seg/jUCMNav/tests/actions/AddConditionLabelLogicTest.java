package seg.jUCMNav.tests.actions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import seg.jUCMNav.actions.AddConditionLabelAction;
import urncore.Condition;
import urncore.UrncoreFactory;

/**
 * Regression test for the operator-precedence NPE in
 * {@link AddConditionLabelAction}. The StartPoint / EndPoint branches read
 * {@code c != null && c.getLabel() == null || c.getLabel().length() == 0}; because
 * {@code &&} binds tighter than {@code ||}, a null pre/postcondition (e.g. selecting
 * an EndPoint with no postcondition) reached {@code null.getLabel()} and NPEd on
 * every selection change. The shared {@link AddConditionLabelAction#needsLabel}
 * helper is correctly parenthesized and null-safe.
 *
 * @author Claude (QA modernization)
 */
public class AddConditionLabelLogicTest {

    @Test
    public void nullConditionNeedsNoLabelAndDoesNotThrow() {
        // This is the exact case that used to NPE (no pre/postcondition on the node).
        assertFalse(AddConditionLabelAction.needsLabel(null));
    }

    @Test
    public void conditionWithoutLabelNeedsOne() {
        Condition c = UrncoreFactory.eINSTANCE.createCondition();
        assertTrue("a condition with a null label should accept one", AddConditionLabelAction.needsLabel(c)); //$NON-NLS-1$
        c.setLabel(""); //$NON-NLS-1$
        assertTrue("a condition with an empty label should accept one", AddConditionLabelAction.needsLabel(c)); //$NON-NLS-1$
    }

    @Test
    public void labelledConditionDoesNotNeedOne() {
        Condition c = UrncoreFactory.eINSTANCE.createCondition();
        c.setLabel("guard"); //$NON-NLS-1$
        assertFalse(AddConditionLabelAction.needsLabel(c));
    }
}
