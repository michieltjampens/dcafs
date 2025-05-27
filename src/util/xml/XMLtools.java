package util.xml;

import org.tinylog.Logger;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

public class XMLtools {

	private XMLtools() {
		throw new IllegalStateException("Utility class");
	}
	/**
	 * Read and parse an XML file to a Document, returning an empty optional on error
	 * 
	 * @param xml The path to the file
	 * @return The Document of the XML
	 */
	public static Optional<Document> readXML( Path xml ) {

		if(Files.notExists(xml)){
			Logger.error("No such file: "+xml);
			return Optional.empty();
		}

		var dbf = createDocFactory().orElse(null);
		if( dbf==null)
			return Optional.empty();

		try {
			Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
			doc.getDocumentElement().normalize();
			return Optional.of(doc);
		} catch (ParserConfigurationException | SAXException | IOException | java.nio.file.InvalidPathException e) {
			Logger.error("Error occurred while reading " + xml, true);
			Logger.error(e.toString());
			return Optional.empty();
		}
	}
	/**
	 * Create an empty xml file and return the Document to fill in
	 *
	 * @param xmlFile The path to the file to create
	 * @param write True if the actual file needs to be created already
	 * @return The document
	 */
	public static Optional<Document> createXML(Path xmlFile, boolean write) {

		var dbfOpt = createDocFactory();
		if( dbfOpt.isEmpty())
			return Optional.empty();
		var dbf = dbfOpt.get();

		try {
			Document doc = dbf.newDocumentBuilder().newDocument();

			if (write) {
				writeXML(xmlFile, doc);
			}
			return Optional.of(doc);
		} catch (ParserConfigurationException | java.nio.file.InvalidPathException e){
			Logger.error("Error occurred while creating XML" + xmlFile.getFileName().toString(),true);
			return Optional.empty();
		} catch (NullPointerException e){
			Logger.error(e);
			return Optional.empty();
		}
	}
	/**
	 * Does the same as readXML except it returns the error that occurred
	 * @param xml Path to the xml
	 * @return The error message or empty string if none
	 */
	public static String checkXML( Path xml ){

		if(Files.notExists(xml)){
			Logger.error("No such file: "+xml);
			return "No such file: "+xml;
		}

		var dbfOpt = createDocFactory();
		if( dbfOpt.isEmpty())
			return "Failed to create factory";
		var dbf = dbfOpt.get();

		try {
			Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
			doc.getDocumentElement().normalize();
		} catch (ParserConfigurationException | SAXException | IOException | java.nio.file.InvalidPathException e) {
			var error = e.toString().replace("lineNumber: ", "line:").replace("; columnNumber","");
			error = error.substring(error.indexOf(":")+1).replace(": ",":").trim();

			if( error.startsWith("file:")){
				var file = error.substring(6,error.indexOf(";"));
				file = Path.of(file).getFileName().toString();
				error = file+":"+error.substring(error.indexOf(";")+1);
			}
			return error;
		}
		return "";
	}

	/**
	 * Create a document factory to make/read the xml file
	 * @return The factory if nothing bad happened
	 */
	private static Optional<DocumentBuilderFactory> createDocFactory(){
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

		try {
			dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
			dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		} catch (ParserConfigurationException e) {
			Logger.error("xml -> Failed to parse");
			return Optional.empty();
		}
		return Optional.of(dbf);
	}
	/**
	 * Write the content of a Document to an xml file
	 *
	 * @param xmlFile The file to write to
	 * @param xmlDoc  The content to write in the file
	 */
	public static void writeXML(Path xmlFile, Document xmlDoc) {
		if( xmlDoc == null )
			return;

		try ( var fos = new FileOutputStream(xmlFile.toFile());
			  var writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)
		){
			Source source = new DOMSource(xmlDoc);
			XMLtools.cleanXML(xmlDoc);

			StreamResult result = new StreamResult(writer);
			
			TransformerFactory tFactory = TransformerFactory.newInstance();
			tFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); // Compliant
			tFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); // Compliant
			
			Transformer xformer = tFactory.newTransformer();
			xformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			xformer.setOutputProperty(OutputKeys.INDENT, "yes");
			xformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			xformer.transform(source, result);
		} catch (Exception e) {
			Logger.error("Failed writing XML: "+xmlFile);
			Logger.error(e);
		}
	}
	/**
	 * Write the xmldoc to the file it was read from
	 *
	 * @param xmlDoc The updated document
	 */
	public static void updateXML(Document xmlDoc ){
		try {
			XMLtools.writeXML(Path.of( new URL(xmlDoc.getDocumentURI()).toURI() ), xmlDoc);
		} catch (URISyntaxException | MalformedURLException e) {
			Logger.error(e);
		}
	}

	/**
	 * Do a clean of the xml document according to xpathfactory
	 *
	 * @param xmlDoc The document to clean
	 */
	public static void cleanXML(Document xmlDoc) {
		XPathFactory xpathFactory = XPathFactory.newInstance();
		// XPath to find empty text nodes.
		XPathExpression xpathExp;
		try {
			xpathExp = xpathFactory.newXPath().compile("//text()[normalize-space(.) = '']");
			NodeList emptyTextNodes = (NodeList) xpathExp.evaluate(xmlDoc, XPathConstants.NODESET);

			// Remove each empty text node from document.
			for (int i = 0; i < emptyTextNodes.getLength(); i++) {
				Node emptyTextNode = emptyTextNodes.item(i);
				emptyTextNode.getParentNode().removeChild(emptyTextNode);
			}
		} catch (XPathExpressionException e) {
			Logger.error(e);
		}
	}
	/* *********************************  S E A R C H I N G *******************************************************/
	/* ************************************** Child ******************************************************* */
	/**
	 * Retrieve the first child from an element with a specific tag
	 *
	 * @param element The Element to check
	 * @param tag     The name of the node in the element
	 * @return The element if found, null if not
	 */
	public static Optional<Element> getFirstChildByTag(Element element, String tag) {
		if( element == null ){
			Logger.error("Element is null when looking for "+tag);
			return Optional.empty();
		}
		if (tag == null) {
			Logger.error("Tag is null");
			return Optional.empty();
		}
		var list = element.getChildNodes();
		return IntStream.range(0, list.getLength())
				.mapToObj(list::item)
				.filter(node -> node instanceof Element)
				.map(node -> (Element) node)
				.filter(ele -> ele.getTagName().equalsIgnoreCase(tag) || tag.equals("*"))
				.findFirst();
	}
	/**
	 * Get all the child-elements of an element with the given name
	 * 
	 * @param element The element to look in to
	 * @param child   The names of the child-elements to look for
	 * @return An arraylist with the child-elements or an empty one if none were found
	 */
	public static List<Element> getChildElements(Element element, String... child) {

		if (element == null)
			return new ArrayList<>();

		if( child.length==1 && (child[0].isEmpty()) )
			child[0] = "*";

		return Arrays.stream(child)
				.map(element::getElementsByTagName) // Map to Elements with that tagname
				.flatMap(list -> IntStream.range(0, list.getLength()).mapToObj(list::item))// Extract the items
				.filter(node -> node.getNodeType() == Node.ELEMENT_NODE)// Remove non-element nodes
				.map(node -> (Element) node)// Cast the remainder to Element
				.filter(ele -> ele.getParentNode().equals(element))// Remove those that don't have element as parent node
				.toList(); // Add the matches to an immutable list
	}
	/**
	 * Get all the childnodes from the given element that are of the type element node
	 * @param element The parent node/element
	 * @return An array containing the child elements
	 */
	public static List<Element> getChildElements(Element element) {
		return getChildElements(element,"*");
	}

	/* ********************************* W R I T I N G **************************************/

	/**
	 * Create an empty child node in the given parent
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created element if successful or empty optional if failed
	 */
	public static Optional<Element> createChildElement( Document xmlDoc, Element parent, String node ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return Optional.empty();
		}
		try{
			return Optional.of((Element) parent.appendChild( xmlDoc.createElement(node) ));
		}catch( DOMException e){
            Logger.error("Node:" + node, e);
			return Optional.empty();
		}
	}

	/**
	 * Create a child node in the given parent with the given text content
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created node if successful or null if failed
	 */
	public static Optional<Element> createChildTextElement( Document xmlDoc, Element parent, String node, String content ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return Optional.empty();
		}

		try{			
			Element ele = xmlDoc.createElement(node);
            if (!content.isEmpty())
                ele.appendChild(xmlDoc.createTextNode(content));
			parent.appendChild(ele);
			return Optional.of(ele);
		}catch( DOMException e){
			Logger.error(e);
			return Optional.empty();
		}		
	}
}
