package seg.jUCMNav.importexport;

import java.io.FileOutputStream;

import org.eclipse.draw2d.IFigure;

import grl.BeliefLink;
import grl.LinkRef;
import seg.jUCMNav.extensionpoints.IUseCaseMapExport;
import seg.jUCMNav.model.util.MetadataHelper;
import seg.jUCMNav.views.preferences.AutoLayoutPreferences;
import ucm.map.UCMmap;
import urncore.IURNConnection;
import urncore.IURNContainerRef;
import urncore.IURNDiagram;
import urncore.IURNNode;
import urncore.URNmodelElement;

/**
 * Export the layout information in a DOT file.
 * 
 * @author jkealey
 * 
 */
public class ExportLayoutDOT implements IUseCaseMapExport {
	static int id = 0;

	/**
	 * Recursive method that builds a DOT cluster using the ContainerRef relationship.
	 * 
	 * @param contRef
	 *            the parent
	 * @param dot
	 *            where to write the output.
	 */
	/**
	 * Roughly what a GRL intentional element or a feature occupies on screen, in inches, used when
	 * nothing better is known.
	 *
	 * A node whose size is unknown used to be declared 0 x 0, which asks Graphviz to lay out points
	 * and guarantees that the real figures overlap once they are drawn. A wrong-but-plausible size
	 * gives a usable diagram; zero cannot.
	 */
	private static final double DEFAULT_WIDTH = 1.5;
	private static final double DEFAULT_HEIGHT = 0.85;

	/**
	 * A node with its size, from the dimensions harvested off the figures where they were
	 * available and a sane default where they were not.
	 */
	private static String nodeDeclaration(URNmodelElement node) {
		double width = DEFAULT_WIDTH;
		double height = DEFAULT_HEIGHT;

		String w = MetadataHelper.getMetaData(node, "_width"); //$NON-NLS-1$
		String h = MetadataHelper.getMetaData(node, "_height"); //$NON-NLS-1$
		if (w != null && h != null) {
			try {
				double measuredWidth = Double.parseDouble(w) / 72.0;
				double measuredHeight = Double.parseDouble(h) / 72.0;
				if (measuredWidth > 0 && measuredHeight > 0) {
					width = measuredWidth;
					height = measuredHeight;
				}
			} catch (NumberFormatException keepTheDefault) {
				// metadata written by an older version, or by hand
			}
		}

		return AutoLayoutPreferences.URNODEPREFIX + node.getId()
				+ " [label=\"\", height=\"" + height + "\", width=\"" + width + "\"];\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static void buildCluster(IURNContainerRef contRef, StringBuffer dot) {

		dot.append("subgraph " + AutoLayoutPreferences.CONTAINERPREFIX + ((URNmodelElement) contRef).getId() + " {\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
		dot.append("CheapTrick" + id++ + " [label=\"\", pos=\"\", width=\""+ contRef.getWidth()/72.0 +"\", height=\""+ contRef.getHeight()/72.0 +"\"];\n"); //$NON-NLS-1$ //$NON-NLS-2$

		IURNContainerRef child;
		for (int i = 0; i < contRef.getChildren().size(); i++) {
			child = (IURNContainerRef) contRef.getChildren().get(i);
			buildCluster(child, dot);
		}
		for (int i = 0; i < contRef.getNodes().size(); i++) {
			URNmodelElement node = (URNmodelElement) contRef.getNodes().get(i);

			// Sized like any other node. Emitting a bare name here gave every element inside an
			// actor Graphviz's default 0.75 x 0.5 inch ellipse, while a GRL intentional element is
			// drawn about twice that -- so dot packed them at a spacing they do not fit in and the
			// boxes overlapped on screen. Actors hold most of a GRL diagram, so this was most of it.
			dot.append(nodeDeclaration(node));
		}

		dot.append("} \n"); //$NON-NLS-1$
	}

	/**
	 * Returns a string representation of a Graphviz dot file format which includes the layout information for our use case map.
	 * 
	 * @param diagram
	 *            the map to be converted to Graphviz dot file format.
	 */
	public static String convertURNToDot(IURNDiagram diagram) {
		int i;
		StringBuffer dot = new StringBuffer();
		String rankdir = AutoLayoutPreferences.getOrientation();
		String size = AutoLayoutPreferences.getWidth() + "," + AutoLayoutPreferences.getHeight(); //$NON-NLS-1$

		if (!(diagram instanceof UCMmap)) {
			dot.append("digraph " + AutoLayoutPreferences.DIAGPREFIX + ((URNmodelElement) diagram).getId() + " {\nrankdir=\"" + rankdir + "\";\nsize=\"" + size + "\";\nranksep=\"1.0\";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		} else {
			dot.append("digraph " + AutoLayoutPreferences.DIAGPREFIX + ((URNmodelElement) diagram).getId() + " {\nrankdir=\"" + rankdir + "\";\nsize=\"" + size + "\";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$            
		}
		for (i = 0; i < diagram.getContRefs().size(); i++) {
			IURNContainerRef contRef = (IURNContainerRef) diagram.getContRefs().get(i);
			// we only want root components/actors
			if (contRef.getParent() == null) {
				buildCluster(contRef, dot);
			}
		}

		for (i = 0; i < diagram.getNodes().size(); i++) {
			IURNNode node = (IURNNode) diagram.getNodes().get(i);
			// we only want loose nodes containers
			if (node.getContRef() == null) {
				dot.append(nodeDeclaration((URNmodelElement) node));
			}
		}

		for (i = 0; i < diagram.getConnections().size(); i++) {
			IURNConnection conn = (IURNConnection) diagram.getConnections().get(i);
			if (conn instanceof LinkRef || conn instanceof BeliefLink) {
				dot.append(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) conn.getTarget()).getId()
						+ "->" + AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) conn.getSource()).getId() + ";\n"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				dot.append(AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) conn.getSource()).getId()
						+ "->" + AutoLayoutPreferences.URNODEPREFIX + ((URNmodelElement) conn.getTarget()).getId() + ";\n"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		dot.append("}\n"); //$NON-NLS-1$
		// System.out.println(dot.toString());
		return dot.toString();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see seg.jUCMNav.extensionpoints.IUseCaseMapExport#export(org.eclipse.draw2d.IFigure, java.io.FileOutputStream)
	 */
	public void export(IFigure map, FileOutputStream fos) {
		// not used.
	}

	public void export(IFigure map, String path) {
		// not used.
	}

	/**
	 * Generate a DOT layout file with the given model instance.
	 * 
	 * @see seg.jUCMNav.extensionpoints.IUseCaseMapExport#export(IURNDiagram, java.io.FileOutputStream)
	 */
	public void export(IURNDiagram diagram, FileOutputStream fos) {
		id = 0;
		//if (diagram instanceof UCMmap) {
		String contents = convertURNToDot(diagram);
		try {
			fos.write(contents.getBytes());
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		//}
}

	public void export(IURNDiagram diagram, String path) {
		// not used.
	}
}
