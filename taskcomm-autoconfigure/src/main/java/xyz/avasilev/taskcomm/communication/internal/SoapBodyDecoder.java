package xyz.avasilev.taskcomm.communication.internal;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.core.ResolvableType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Разбор тела SOAP по умолчанию (docs/adr/0135-communication-engine.md): снимает конверт
 * {@code Envelope / Body} SOAP 1.1 или 1.2 и разбирает XML-маппером первый дочерний элемент
 * {@code Body}. Тело без конверта — неразбираемое.
 *
 * <p>Класс грузится, только если {@code jackson-dataformat-xml} в classpath: на него ссылается лишь
 * код за проверкой реестра. DTD запрещены — внешние сущности в чужом XML не разворачиваются.
 */
final class SoapBodyDecoder {

    private static final Set<String> ENVELOPE_NAMESPACES =
            Set.of("http://schemas.xmlsoap.org/soap/envelope/", "http://www.w3.org/2003/05/soap-envelope");

    private final XmlMapper mapper = XmlMapper.builder().build();

    private final DocumentBuilderFactory documents;

    SoapBodyDecoder() {
        this.documents = DocumentBuilderFactory.newInstance();
        this.documents.setNamespaceAware(true);
        this.documents.setExpandEntityReferences(false);
        try {
            this.documents.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (final ParserConfigurationException unsupported) {
            throw new IllegalStateException("XML-парсер JDK не поддерживает запрет DTD", unsupported);
        }
    }

    /**
     * Снимает конверт и разбирает содержимое {@code Body}.
     *
     * @param body сырое тело SOAP
     * @param type тип из дженерика процессора
     * @return доменная форма
     * @throws IllegalArgumentException если тело — не SOAP-конверт с содержимым в {@code Body}
     */
    Object decode(final String body, final ResolvableType type) {
        final String payload = payload(this.parse(body));
        return this.mapper.readValue(payload, this.mapper.getTypeFactory().constructType(type.getType()));
    }

    private Document parse(final String body) {
        try {
            final DocumentBuilder builder = this.documents.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(body)));
        } catch (final ParserConfigurationException | SAXException | IOException failure) {
            throw new IllegalArgumentException("Тело не разбирается как XML: " + failure.getMessage(), failure);
        }
    }

    private static String payload(final Document document) {
        final Element envelope = document.getDocumentElement();
        if (!"Envelope".equals(envelope.getLocalName()) || !ENVELOPE_NAMESPACES.contains(envelope.getNamespaceURI())) {
            throw new IllegalArgumentException("Тело — не SOAP-конверт: корневой элемент " + envelope.getNodeName()
                    + " в пространстве " + envelope.getNamespaceURI());
        }
        final Element content = firstChild(firstChild(envelope, "Body"), null);
        try {
            final Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            final StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(content), new StreamResult(writer));
            return writer.toString();
        } catch (final TransformerException failure) {
            throw new IllegalArgumentException("Содержимое Body не записывается: " + failure.getMessage(), failure);
        }
    }

    /** Первый дочерний элемент с этим локальным именем, или первый любой при {@code null}. */
    private static Element firstChild(final Element parent, final String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof final Element element
                    && (localName == null || localName.equals(element.getLocalName()))) {
                return element;
            }
        }
        throw new IllegalArgumentException(
                "В SOAP-конверте нет элемента " + (localName == null ? "внутри " + parent.getLocalName() : localName));
    }
}
