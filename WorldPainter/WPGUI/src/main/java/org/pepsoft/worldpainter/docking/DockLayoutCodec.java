package org.pepsoft.worldpainter.docking;

import java.io.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Validates persisted layout XML before the docking library can resolve its contents. */
final class DockLayoutCodec {
    static final int MAX_BYTES = 4 * 1024 * 1024;

    static Document parse(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid docking layout size");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if (!document.getDocumentElement().getTagName().equals("app-layout")) {
            throw new IOException("Unsupported docking layout format");
        }
        return document;
    }

    static byte[] rename(byte[] bytes, String from, String to) throws Exception {
        Document document = parse(bytes);
        var elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            for (String name : new String[] {"persistentID", "max-dockable", "anchor"}) {
                if (element.getAttribute(name).equals(from)) element.setAttribute(name, to);
            }
            if (element.getTagName().equals("dockable") && element.getAttribute("id").equals(from)) {
                element.setAttribute("id", to);
            }
        }
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(out));
        return out.toByteArray();
    }

    private DockLayoutCodec() { }
}
