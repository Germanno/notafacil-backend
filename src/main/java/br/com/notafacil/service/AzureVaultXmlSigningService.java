package br.com.notafacil.service;

import br.com.notafacil.exception.XmlSigningException;
import br.com.notafacil.strategy.CertificateAliasStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

@Service
public class AzureVaultXmlSigningService {

    private static final Logger log = LoggerFactory.getLogger(AzureVaultXmlSigningService.class);

    private final KeyStore keyStore;
    private final CertificateAliasStrategy aliasStrategy;
    private final XMLSignatureFactory signatureFactory;
    private final KeyInfoFactory keyInfoFactory;
    private final DocumentBuilderFactory documentBuilderFactory;
    private final TransformerFactory transformerFactory;

    public AzureVaultXmlSigningService(KeyStore keyStore, CertificateAliasStrategy aliasStrategy) {
        this.keyStore = keyStore;
        this.aliasStrategy = aliasStrategy;

        // Initialize factories once for potential reuse
        this.signatureFactory = XMLSignatureFactory.getInstance("DOM");
        this.keyInfoFactory = signatureFactory.getKeyInfoFactory();

        this.documentBuilderFactory = DocumentBuilderFactory.newInstance();
        this.documentBuilderFactory.setNamespaceAware(true); // Crucial for XMLDSig

        this.transformerFactory = TransformerFactory.newInstance();

        log.info("AzureVaultXmlSigningService initialized.");
    }

    /**
     * Signs an XML document using an enveloped signature, referencing the element with the specified ID.
     *
     * @param unsignedXml The XML content as a string.
     * @param elementIdToSign The value of the ID attribute of the element to be signed.
     * @param elementNamespace Optional namespace URI of the element to sign (use null if not namespaced or for any namespace).
     * @param elementLocalName Optional local name of the element to sign (use null if only ID is needed).
     * @param idAttributeName The name of the attribute holding the ID (e.g., "Id").
     * @return The signed XML content as a string.
     * @throws XmlSigningException If any error occurs during the signing process.
     */
    public String signXmlEnvelopedById(String unsignedXml, String elementIdToSign, String elementNamespace, String elementLocalName, String idAttributeName) throws XmlSigningException {
        String alias = null;
        try {
            alias = aliasStrategy.resolveAlias();
            log.info("Resolved certificate alias for signing: {}", alias);

            // 1. Get Key & Certificate from KeyStore
            KeyStore.PrivateKeyEntry keyEntry = getKeyEntry(alias);
            PrivateKey privateKey = keyEntry.getPrivateKey();
            X509Certificate certificate = (X509Certificate) keyEntry.getCertificate();
            log.debug("Retrieved private key and certificate for alias: {}", alias);

            // 2. Parse XML String to DOM Document
            Document doc = parseXml(unsignedXml);

            // 3. Prepare Signature Context
            // Find the element to sign and set its ID attribute
            Element elementToSign = findAndPrepareElementById(doc, elementIdToSign, elementNamespace, elementLocalName, idAttributeName);
            // Sign context will be the parent of the element being signed
            DOMSignContext signContext = new DOMSignContext(privateKey, elementToSign.getParentNode());
            // Place signature element before the element being signed (common practice)
            signContext.setNextSibling(elementToSign);
            log.debug("DOMSignContext created for element ID: {}", elementIdToSign);

            // 4. Create XML Signature Structure
            XMLSignature signature = createXMLSignature(certificate, "#" + elementIdToSign);
            log.debug("XMLSignature structure created.");

            // 5. Generate the Signature
            signature.sign(signContext);
            log.info("XML Signature generated successfully for element ID: {}", elementIdToSign);

            // 6. Serialize Signed DOM back to String
            return serializeXml(doc);

        } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableEntryException | CertificateException e) {
            log.error("Error retrieving key/certificate for alias '{}' from KeyStore", alias, e);
            throw new XmlSigningException("Failed to retrieve signing key/certificate for alias: " + alias, e);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.error("Error parsing unsigned XML", e);
            throw new XmlSigningException("Failed to parse the XML document", e);
        } catch (MarshalException | XMLSignatureException e) {
            log.error("Error generating XML Signature", e);
            throw new XmlSigningException("Failed to generate the XML Signature", e);
        } catch (TransformerException e) {
            log.error("Error serializing signed XML", e);
            throw new XmlSigningException("Failed to serialize the signed XML document", e);
        } catch (Exception e) { // Catch any other unexpected exceptions
            log.error("Unexpected error during XML signing for alias '{}'", alias, e);
            throw new XmlSigningException("An unexpected error occurred during XML signing", e);
        }
    }

    /**
     * Overload for signing the LoteRps element specifically, as used in the original code.
     *
     * @param unsignedXml The XML content as a string.
     * @param loteRpsId The value of the 'Id' attribute of the LoteRps element.
     * @return The signed XML content as a string.
     * @throws XmlSigningException If any error occurs during the signing process.
     */
    public String signLoteRps(String unsignedXml, String loteRpsId) throws XmlSigningException {
        // Assuming LoteRps element might not have a specific namespace prefix in the source XML,
        // but we know its local name and the ID attribute name.
        // Use null for namespace to match any.
        return signXmlEnvelopedById(unsignedXml, loteRpsId, null, "LoteRps", "Id");
    }

    // --- Private Helper Methods ---

    private KeyStore.PrivateKeyEntry getKeyEntry(String alias) throws NoSuchAlgorithmException, UnrecoverableEntryException, KeyStoreException {
        KeyStore.ProtectionParameter protectionParam = null; // No password needed for Azure Key Vault JCA typically
        KeyStore.Entry entry = keyStore.getEntry(alias, protectionParam);
        if (entry == null) {
            throw new KeyStoreException("No key entry found for alias: " + alias);
        }
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) {
            throw new KeyStoreException("Entry for alias '" + alias + "' is not a PrivateKeyEntry");
        }
        return (KeyStore.PrivateKeyEntry) entry;
    }

    private Document parseXml(String xml) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilder db = documentBuilderFactory.newDocumentBuilder();
        return db.parse(new InputSource(new StringReader(xml)));
    }

    private Element findAndPrepareElementById(Document doc, String idValue, String namespaceURI, String localName, String idAttributeName) throws XmlSigningException {
        NodeList elements;
        if (namespaceURI != null && localName != null) {
            elements = doc.getElementsByTagNameNS(namespaceURI, localName);
        } else if (localName != null) {
            elements = doc.getElementsByTagNameNS("*", localName); // Wildcard namespace
        } else {
            // Fallback: Search all elements if only ID is known (less efficient)
            elements = doc.getElementsByTagName("*");
        }

        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (idValue.equals(element.getAttribute(idAttributeName))) {
                // IMPORTANT: Explicitly declare the ID attribute for the XMLDSig library
                element.setIdAttribute(idAttributeName, true);
                log.debug("Found element '{}' with ID '{}' and set '{}' as ID attribute.", element.getTagName(), idValue, idAttributeName);
                return element;
            }
        }
        throw new XmlSigningException("Could not find element with " + idAttributeName + "='" + idValue + "'" +
                                      (localName != null ? " and local name '" + localName + "'" : ""));
    }

    private XMLSignature createXMLSignature(X509Certificate certificate, String referenceURI) throws NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        // 3. Create Reference to the element to be signed (using its ID)
        List<Transform> transforms = Collections.singletonList(
                signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)
                // Optional: Add Canonicalization transform if needed (e.g., C14N)
                // signatureFactory.newTransform(CanonicalizationMethod.INCLUSIVE, (TransformParameterSpec) null)
        );
        Reference ref = signatureFactory.newReference(
                referenceURI, // URI identifies the element to sign (e.g., "#elementId")
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
                transforms,
                null,
                null // Reference ID (optional)
        );

        // 4. Create SignedInfo (Canonicalization, Signature Method, Reference(s))
        SignedInfo si = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(
                        CanonicalizationMethod.INCLUSIVE, // Or C14N11, EXC_C14N etc.
                        (C14NMethodParameterSpec) null
                ),
                signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                Collections.singletonList(ref)
        );

        // 5. Create KeyInfo containing the X.509 certificate
        X509Data x509Data = keyInfoFactory.newX509Data(Collections.singletonList(certificate));
        KeyInfo ki = keyInfoFactory.newKeyInfo(Collections.singletonList(x509Data));

        // 6. Create the XMLSignature (combining SignedInfo and KeyInfo)
        return signatureFactory.newXMLSignature(si, ki);
    }

    private String serializeXml(Document doc) throws TransformerException {
        Transformer transformer = transformerFactory.newTransformer();
        // Configuration for output format
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no"); // Keep declaration
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        // Indentation is helpful for debugging but should usually be off for production SOAP messages
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));
        log.debug("Signed XML serialized successfully.");
        return sw.toString();
    }
}

