package seg.jUCMNav.tests.Z151importexport;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import grl.Belief;
import grl.GRLGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import seg.jUCMNav.importexport.z151.generated.ObjectFactory;
import seg.jUCMNav.importexport.z151.marshal.URNspecMHandler;
import seg.jUCMNav.importexport.z151.unmarshal.EObjectImplUMHandler;
import seg.jUCMNav.importexport.z151.unmarshal.URNspecUMHandler;
import seg.jUCMNav.model.ModelCreationFactory;
import seg.jUCMNav.model.commands.create.AddBeliefCommand;
import urn.URNspec;

/**
 * Regression test for issue #2: Z.151 export must preserve GRL belief author and
 * size data across the schema-drift that happened during the JAXB migration
 * (Z.151 v20120902 inverted IntentionalElement.refs into the
 * IntentionalElementRef.def IDREF; a marshal-handler line was deleted with a FLAG
 * comment over a worry that author/size might be dropped).
 *
 * Builds a GRL model with two authored beliefs, exports it to Z.151, and asserts
 * the author metadata and size element survive. Then it reimports and re-exports
 * and asserts the authors are STILL present -- proving the import side restores
 * them (IntentionalElementRefUMHandler reads the "jUCMNav Belief author"
 * metadata back into Belief.setAuthor), i.e. the full round-trip is lossless.
 *
 * @author Claude (QA modernization, issue #2)
 */
public class Z151BeliefRoundTripTest {

    @Test
    public void testBeliefAuthorAndSizeSurviveRoundTrip() throws Exception {
        URNspec urnspec = ModelCreationFactory.getNewURNspec(false, true, false);
        GRLGraph graph = firstGrlGraph(urnspec);
        assertNotNull("expected a GRLGraph in the new URNspec", graph); //$NON-NLS-1$

        newBelief(urnspec, graph, "Quality", "Alice"); //$NON-NLS-1$ //$NON-NLS-2$
        newBelief(urnspec, graph, "Cost", "Bob"); //$NON-NLS-1$ //$NON-NLS-2$

        String xml = exportToZ151(urnspec);

        // Issue #2: the author metadata and the size element must be exported.
        assertContains(xml, "jUCMNav Belief author"); //$NON-NLS-1$
        assertContains(xml, "Alice"); //$NON-NLS-1$
        assertContains(xml, "Bob"); //$NON-NLS-1$
        assertContains(xml, "<size>"); //$NON-NLS-1$

        // Full round-trip: reimport, then re-export. If the import side dropped the
        // author, the second export would not contain it.
        URNspec reimported = importFromZ151(xml);
        String xml2 = exportToZ151(reimported);
        assertContains(xml2, "jUCMNav Belief author"); //$NON-NLS-1$
        assertContains(xml2, "Alice"); //$NON-NLS-1$
        assertContains(xml2, "Bob"); //$NON-NLS-1$
    }

    private Belief newBelief(URNspec urnspec, GRLGraph graph, String name, String author) {
        Belief b = (Belief) ModelCreationFactory.getNewObject(urnspec, Belief.class);
        b.setName(name);
        new AddBeliefCommand(graph, b).execute();
        b.setAuthor(author);
        return b;
    }

    private GRLGraph firstGrlGraph(URNspec urnspec) {
        for (Object d : urnspec.getUrndef().getSpecDiagrams())
            if (d instanceof GRLGraph)
                return (GRLGraph) d;
        return null;
    }

    private void assertContains(String haystack, String needle) {
        assertTrue("exported Z.151 is missing \"" + needle + "\":\n" + haystack, haystack.contains(needle)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String exportToZ151(URNspec urn) throws Exception {
        URNspecMHandler mh = new URNspecMHandler();
        try {
            seg.jUCMNav.importexport.z151.generated.URNspec urnZ = (seg.jUCMNav.importexport.z151.generated.URNspec) mh
                    .handle(urn, null, true);
            JAXBContext context = JAXBContext.newInstance(seg.jUCMNav.importexport.z151.generated.URNspec.class);
            ObjectFactory of = new ObjectFactory();
            JAXBElement<seg.jUCMNav.importexport.z151.generated.URNspec> spec = of.createURNspec(urnZ);
            Marshaller m = context.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            StringWriter sw = new StringWriter();
            m.marshal(spec, sw);
            return sw.toString();
        } finally {
            mh.resetUrnSpec();
        }
    }

    @SuppressWarnings("unchecked")
    private URNspec importFromZ151(String xml) throws Exception {
        EObjectImplUMHandler mh = new URNspecUMHandler();
        try {
            JAXBContext context = JAXBContext.newInstance(seg.jUCMNav.importexport.z151.generated.URNspec.class);
            Unmarshaller um = context.createUnmarshaller();
            JAXBElement<seg.jUCMNav.importexport.z151.generated.URNspec> specFromFile = (JAXBElement<seg.jUCMNav.importexport.z151.generated.URNspec>) um
                    .unmarshal(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return (URNspec) mh.handle(specFromFile.getValue(), null, true);
        } finally {
            mh.resetUrnSpec();
        }
    }
}
