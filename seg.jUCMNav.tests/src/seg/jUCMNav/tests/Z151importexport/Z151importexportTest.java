package seg.jUCMNav.tests.Z151importexport;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

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
import seg.jUCMNav.importexport.z151.generated.URNspec;
import seg.jUCMNav.importexport.z151.marshal.MHandler;
import seg.jUCMNav.importexport.z151.marshal.URNspecMHandler;
import seg.jUCMNav.importexport.z151.unmarshal.EObjectImplUMHandler;
import seg.jUCMNav.importexport.z151.unmarshal.URNspecUMHandler;

/**
 * Z.151 import/export round-trip regression gate.
 *
 * For each fixture, the expected {@code .z151} file is read from the test bundle
 * classpath (the sibling {@code expected/} folder), imported into a jUCMNav URN
 * model, re-exported to a throwaway temporary file, and the two are compared
 * after normalizing the generated ids. This is the formal regression gate for
 * commits 301a9711 (preserve GRL ref relationships on export) and 8f6f727c
 * (guard the optional style element on import).
 *
 * Re-enabled in issue #7: the previous version resolved expected/actual paths
 * against {@code new File(".")} with hard-coded Windows separators, which does
 * not exist under the Tycho-surefire test workspace. It now loads the fixture as
 * a bundle resource and writes output to a temp file, so it is location- and
 * platform-independent.
 */
public class Z151importexportTest {

    @Test
    public void testActor() throws Exception {
        compareTwoZ151File("actor.z151"); //$NON-NLS-1$
    }

    public void compareTwoZ151File(String Z151file) throws Exception {
        // Expected file: load from the test bundle classpath (sibling 'expected' folder).
        byte[] expectedBytes = readResource("expected/" + Z151file); //$NON-NLS-1$

        // Import the expected Z.151 document into a jUCMNav URN model.
        urn.URNspec urn = mockImport(new ByteArrayInputStream(expectedBytes));
        assertNotNull("Z.151 import returned no URNspec for " + Z151file, urn); //$NON-NLS-1$

        // Re-export to a throwaway temp file -- no dependency on the source-tree layout.
        File actualOut = File.createTempFile("z151-actual-", "-" + Z151file); //$NON-NLS-1$ //$NON-NLS-2$
        actualOut.deleteOnExit();
        mockExport(urn, actualOut.getAbsolutePath());
        byte[] actualBytes = Files.readAllBytes(actualOut.toPath());

        // Normalize line endings: the committed fixture may be checked out with CRLF
        // on Windows, while the JAXB marshaller emits LF. Line endings are not
        // semantically meaningful in XML, so compare on a common newline form.
        String expected = normalizeNewlines(ResolveIds(new String(expectedBytes, StandardCharsets.UTF_8)));
        String actual = normalizeNewlines(ResolveIds(new String(actualBytes, StandardCharsets.UTF_8)));
        assertEquals(expected, actual);
    }

    private static String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Reads a resource located relative to this test class on the bundle classpath. */
    private byte[] readResource(String relativeName) throws IOException {
        InputStream in = Z151importexportTest.class.getResourceAsStream(relativeName);
        assertNotNull("Missing test resource on classpath: " + relativeName, in); //$NON-NLS-1$
        try {
            return in.readAllBytes();
        } finally {
            in.close();
        }
    }

    @SuppressWarnings("unchecked")
    private urn.URNspec mockImport(InputStream in) {
        EObjectImplUMHandler mh = new URNspecUMHandler();
        urn.URNspec urn = null;
        try {
            JAXBContext context = JAXBContext.newInstance(URNspec.class);
            Unmarshaller um = context.createUnmarshaller();
            // Unmarshal XML contents of the stream into a Java object instance.
            JAXBElement<seg.jUCMNav.importexport.z151.generated.URNspec> specFromFile = (JAXBElement<seg.jUCMNav.importexport.z151.generated.URNspec>) um
                    .unmarshal(in);
            urn = (urn.URNspec) mh.handle(specFromFile.getValue(), null, true);
        } catch (JAXBException jbe) {
            System.err.println(jbe.getMessage());
        } catch (Exception e) {
            System.err.println(e.getMessage());
        } finally {
            mh.resetUrnSpec();
        }
        return urn;
    }

    private void mockExport(urn.URNspec urn, String outputFile) {
        MHandler mh = null;
        FileOutputStream fos = null;

        // Marshal the URN spec to XML...
        try {
            fos = new FileOutputStream(outputFile);
            mh = new URNspecMHandler();
            URNspec urnZ = (URNspec) mh.handle(urn, null, true);
            JAXBContext context = JAXBContext.newInstance(URNspec.class);
            ObjectFactory of = new ObjectFactory();
            JAXBElement<URNspec> spec = of.createURNspec(urnZ);
            Marshaller m = context.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            m.marshal(spec, fos);
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        } catch (JAXBException jbe) {
            System.err.println(jbe.getMessage());
        } catch (Exception e) {
            System.err.println(e.getMessage());
        } finally {
            if (mh != null)
                mh.resetUrnSpec();
            if (fos != null)
                try {
                    fos.close();
                } catch (IOException ignore) {
                    // best-effort close
                }
        }
    }

    private String ResolveIds(String urnspec) {
        String idPrefix = "<id>Z151_id_"; //$NON-NLS-1$
        String idClosing = "</id>"; //$NON-NLS-1$
        int count = 1;
        int startIndex = urnspec.indexOf(idPrefix);
        int endIndex;
        while (startIndex != -1) {
            endIndex = startIndex + urnspec.substring(startIndex).indexOf(idClosing);
            String id = urnspec.substring(startIndex + "<id>".length(), endIndex); //$NON-NLS-1$
            String newId = "RESOLVED_" + count++; //$NON-NLS-1$
            urnspec = urnspec.replaceAll(id, newId);
            startIndex = urnspec.indexOf(idPrefix);
        }
        return urnspec;
    }
}
