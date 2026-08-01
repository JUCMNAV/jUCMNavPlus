package seg.jUCMNav.tests.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import seg.jUCMNav.Messages;
import seg.jUCMNav.model.util.URNNamingHelper;
import urn.URNspec;
import urn.UrnFactory;
import urncore.Responsibility;
import urncore.UrncoreFactory;

/**
 * Regression test for legacy bug 925: clearing an element's name reported "that name already
 * exists" instead of saying the name was missing.
 *
 * The cause is that every does...NameExists() helper in {@link URNNamingHelper} ends with
 * {@code return proposedName.length() == 0}, folding "blank" into "already taken".
 * {@code isNameValid} set the correct blank-name message first and then let the uniqueness
 * branch overwrite it. Reported against responsibilities, but it applied to components,
 * actors, intentional elements and KPI information elements the same way.
 *
 * @author Claude
 */
public class NameValidationTest {

    private URNspec urn;
    private Responsibility target;

    @Before
    public void setUp() {
        urn = UrnFactory.eINSTANCE.createURNspec();
        urn.setUrndef(UrncoreFactory.eINSTANCE.createURNdefinition());

        Responsibility existing = UrncoreFactory.eINSTANCE.createResponsibility();
        existing.setName("Validate input"); //$NON-NLS-1$
        urn.getUrndef().getResponsibilities().add(existing);

        target = UrncoreFactory.eINSTANCE.createResponsibility();
        target.setName("Persist order"); //$NON-NLS-1$
        urn.getUrndef().getResponsibilities().add(target);
    }

    @Test
    public void blankNameIsReportedAsMissingNotAsDuplicate() {
        String message = URNNamingHelper.isNameValid(urn, target, ""); //$NON-NLS-1$

        assertEquals("clearing a name should say the name is invalid", //$NON-NLS-1$
                Messages.getString("URNNamingHelper.invalidName"), message); //$NON-NLS-1$
        assertFalse("a blank name is not a duplicate-name problem", //$NON-NLS-1$
                message.equals(Messages.getString("URNNamingHelper.respNameExist"))); //$NON-NLS-1$
    }

    @Test
    public void whitespaceOnlyNameIsTreatedAsBlank() {
        // isNameValid trims before deciding, so these must behave like the empty string.
        assertEquals(Messages.getString("URNNamingHelper.invalidName"), //$NON-NLS-1$
                URNNamingHelper.isNameValid(urn, target, "   ")); //$NON-NLS-1$
        assertEquals(Messages.getString("URNNamingHelper.invalidName"), //$NON-NLS-1$
                URNNamingHelper.isNameValid(urn, target, "\t")); //$NON-NLS-1$
    }

    @Test
    public void nullNameIsTreatedAsBlank() {
        assertEquals(Messages.getString("URNNamingHelper.invalidName"), //$NON-NLS-1$
                URNNamingHelper.isNameValid(urn, target, null));
    }

    @Test
    public void duplicateNameStillReportsTheDuplicate() {
        // The fix must not swallow the case it sits in front of.
        String message = URNNamingHelper.isNameValid(urn, target, "Validate input"); //$NON-NLS-1$

        assertEquals(Messages.getString("URNNamingHelper.respNameExist"), message); //$NON-NLS-1$
    }

    @Test
    public void freshUniqueNameIsAccepted() {
        assertEquals("", URNNamingHelper.isNameValid(urn, target, "Send receipt")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void keepingTheCurrentNameIsAccepted() {
        // Renaming an element to what it is already called must not trip the uniqueness check
        // against itself.
        assertTrue(URNNamingHelper.isNameValid(urn, target, "Persist order").length() == 0); //$NON-NLS-1$
    }
}
