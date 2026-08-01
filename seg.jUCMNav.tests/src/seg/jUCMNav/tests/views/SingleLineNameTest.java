package seg.jUCMNav.tests.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import seg.jUCMNav.model.util.URNNamingHelper;

/**
 * Regression test for multi-line names in the outline and list views.
 *
 * Responsibility and stub names may contain line breaks, but a TreeItem or table cell renders
 * only up to the first one. Several responsibilities whose names begin with the same line were
 * therefore indistinguishable in the hierarchical, definitions and concerns outlines --
 * precisely when telling them apart matters. {@link URNNamingHelper#getSingleLineName(String)}
 * folds the breaks so the whole name fits the one line the widget offers.
 *
 * @author Claude
 */
public class SingleLineNameTest {

    @Test
    public void nullAndSingleLineNamesAreReturnedUntouched() {
        assertNull(URNNamingHelper.getSingleLineName(null));

        String plain = "Send notification"; //$NON-NLS-1$
        assertSame("a name with no line break should not be copied", plain, URNNamingHelper.getSingleLineName(plain)); //$NON-NLS-1$
    }

    @Test
    public void lineBreaksBecomeSpaces() {
        assertEquals("Validate input then persist", //$NON-NLS-1$
                URNNamingHelper.getSingleLineName("Validate input\nthen persist")); //$NON-NLS-1$
        assertEquals("Validate input then persist", //$NON-NLS-1$
                URNNamingHelper.getSingleLineName("Validate input\r\nthen persist")); //$NON-NLS-1$
        assertEquals("a b c", URNNamingHelper.getSingleLineName("a\nb\nc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void namesSharingAFirstLineStayDistinguishable() {
        String first = URNNamingHelper.getSingleLineName("Handle request\nfrom the reviewer"); //$NON-NLS-1$
        String second = URNNamingHelper.getSingleLineName("Handle request\nfrom the author"); //$NON-NLS-1$

        assertEquals("Handle request from the reviewer", first); //$NON-NLS-1$
        assertEquals("Handle request from the author", second); //$NON-NLS-1$
    }

    @Test
    public void runsAndSurroundingWhitespaceCollapseToOneSpace() {
        assertEquals("a b", URNNamingHelper.getSingleLineName("a\n\n\nb")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a b", URNNamingHelper.getSingleLineName("a   \n   b")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a b", URNNamingHelper.getSingleLineName("a\t\n\tb")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("leading and trailing breaks are dropped", //$NON-NLS-1$
                "a b", URNNamingHelper.getSingleLineName("\na\nb\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void spacingWithinALineIsLeftAlone() {
        // Only whitespace runs containing a break are touched, so a name is never altered
        // beyond what the widget could not have shown anyway.
        assertEquals("a  b", URNNamingHelper.getSingleLineName("a  b\nc").substring(0, 4)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a  b c", URNNamingHelper.getSingleLineName("a  b\nc")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
