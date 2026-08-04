package seg.jUCMNav.model.util;

import org.eclipse.draw2d.geometry.Dimension;

import urncore.URNmodelElement;

/**
 * How much room an element and its label take together.
 *
 * <p>
 * A label is part of what an element occupies. Auto-layout has been treating one as a 0.35 inch
 * disc regardless of whether it is called "T" or "Assign Developer", so Graphviz reserved no space
 * for text it was never shown, and the labels then landed on top of each other, on the path, and
 * across component boundaries. Sizing the element by its <i>visual</i> bounds -- the figure plus the
 * label -- is what stops that, and it costs nothing but knowing the name.
 *
 * <p>
 * The estimate is deliberately rough. Exact text metrics need a font, which needs a display, which
 * would tie the layout to a running workbench and make it untestable; and the answer only has to be
 * good enough for Graphviz to leave a gap. A monospace approximation over the longest line is
 * within a character or two of the truth for the names that appear in these models, and erring
 * generous costs a little whitespace where erring mean costs a collision.
 *
 * <p>
 * What this does <b>not</b> model is that a responsibility's label may sit anywhere around it that
 * is free -- above, below, either side -- so reserving a single box around the element is
 * pessimistic for those. It is still far better than reserving nothing, and a label that can move
 * is a placement problem to solve after the layout, not a sizing one.
 *
 * @author Claude
 */
public class LabelExtent {

    /** Rough width of one character at the default diagram font. */
    private static final int CHAR_WIDTH = 7;

    /** Rough height of one line, including leading. */
    private static final int LINE_HEIGHT = 14;

    /** A label never reserves less than this, so an unnamed element still gets a gap. */
    private static final int MIN_WIDTH = 24;

    /**
     * The visual extent of an element: its own figure, widened and heightened to hold its label.
     *
     * @param element
     *            the element, whose name is the label text
     * @param figure
     *            how big the element itself is drawn
     */
    public static Dimension including(URNmodelElement element, Dimension figure) {
        Dimension label = of(element);
        return new Dimension(Math.max(figure.width, label.width), figure.height + label.height);
    }

    /** The extent of an element's label alone, zero when it has no name. */
    public static Dimension of(URNmodelElement element) {
        return of(element == null ? null : element.getName());
    }

    /**
     * The extent of a piece of label text.
     *
     * Multi-line names are common and are exactly the case that goes wrong when ignored: a
     * responsibility called "Assign\nDeveloper" is twice as tall as its figure suggests, and jUCMNav
     * draws every line.
     */
    public static Dimension of(String text) {
        if (text == null || text.length() == 0)
            return new Dimension(0, 0);

        int lines = 1;
        int longest = 0;
        int current = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
                longest = Math.max(longest, current);
                current = 0;
            } else if (c != '\r') {
                current++;
            }
        }
        longest = Math.max(longest, current);

        return new Dimension(Math.max(MIN_WIDTH, longest * CHAR_WIDTH), lines * LINE_HEIGHT);
    }
}
