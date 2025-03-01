package util.xml;

import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.math.NumberUtils;
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
import java.io.*;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
	 * Does the same as readXML except it returns the error that occurred
	 * @param xml Path to the xml
	 * @return The error message or empty string if none
	 */
	public static String checkXML( Path xml ){

		if(Files.notExists(xml)){
			Logger.error("No such file: "+xml);
			return "No such file: "+xml;
		}

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

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
	 * Write the content of a Document to an xml file
	 *
	 * @param xmlFile The file to write to
	 * @param xmlDoc  The content to write in the file
	 */
	public static void writeXML(Path xmlFile, Document xmlDoc) {
		if( xmlDoc == null )
			return;
		boolean isNew = Files.notExists(xmlFile);

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
		// Change it so everyone can alter the files
		/*if( SystemUtils.IS_OS_LINUX && isNew ) {
			try {
				Set<PosixFilePermission> perms = Files.readAttributes(xmlFile, PosixFileAttributes.class).permissions();
				Logger.info("Permissions before:"+  PosixFilePermissions.toString(perms));
				perms.add(PosixFilePermission.OTHERS_WRITE);
				Logger.info("Permissions after: "+  PosixFilePermissions.toString(perms));
				Files.setPosixFilePermissions(xmlFile, perms);
			}catch( Exception e){
				Logger.error(e);
			}
		}*/
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
		var list = element.getChildNodes();
		for( int a=0;a<list.getLength();a++){
			if(list.item(a) instanceof Element ele) {
				if (ele.getTagName().equalsIgnoreCase(tag) || tag.equals("*"))
					return Optional.of(ele);
			}
		}
		return Optional.empty();
	}
	/**
	 * Check the given element for a child node with a specific tag
	 * @param parent The element to look into
	 * @param tag The tag to look for or * for 'any'
	 * @return True if found
	 */
	public static boolean hasChildByTag(Element parent, String tag) {
		return getFirstChildByTag(parent, tag).isPresent();
	}
	/**
	 * Get the string value of a node from the given element with the given tag, returning a default value if none found
	 *
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static String getChildStringValueByTag(Element element, String tag, String def) {
		return getFirstChildByTag(element, tag.toLowerCase()).map(Node::getTextContent).orElse(def);
	}
	/**
	 * Get the integer value of a node from the given element with the given name
	 *
	 * @param element The element to look in
	 * @param tag     The name of the node
	 * @param def     The value to return if the node wasn't found
	 * @return The requested data or the def value if not found
	 */
	public static int getChildIntValueByTag(Element element, String tag, int def) {
		return getFirstChildByTag(element, tag).map(e-> NumberUtils.toInt(e.getTextContent(),def)).orElse(def);
	}
		/**
	 * Get all the child-elements of an element with the given name
	 * 
	 * @param element The element to look in to
	 * @param child   The named of the child-elements to look for
	 * @return An arraylist with the child-elements or an empty one if none were found
	 */
	public static List<Element> getChildElements(Element element, String... child) {

		if (element == null)
			return new ArrayList<>();

		if( child.length==1 && (child[0].isEmpty()) )
			child[0]="*";

		var eles = new ArrayList<Element>();
		for( String ch : child ){
			NodeList list = element.getElementsByTagName(ch);

			for (int a = 0; a < list.getLength(); a++){
				if (list.item(a).getNodeType() == Node.ELEMENT_NODE ) {
				 	Element add = (Element)list.item(a);
					 if( add.getParentNode().equals(element) )
						eles.add((Element) list.item(a));
				}
			}
		}
		eles.trimToSize();
		return eles;
	}
	/**
	 * Get all the childnodes from the given element that are of the type element node
	 * @param element The parent node/element
	 * @return An array containing the child elements
	 */
	public static List<Element> getChildElements(Element element) {
		return getChildElements(element,"*");
	}

	/* ******************************  E L E M E N T   A T T R I B U T E S *********************************/
	/**
	 * Get the attributes of an element and cast to string, return def if failed
	 * @param element The element that might hold the attribute
	 * @param attribute The tag of the attribute
	 * @param def The value to return if cast/parse fails
	 * @return The content if ok or def if failed
	 */
	public static String getStringAttribute(Element element, String attribute, String def) {
		if( element==null){
			Logger.error("Given parent is null while looking for "+attribute);
			return def;
		}
		// If the parent doesn't have the attribute, return the default
		if( !element.hasAttribute(attribute))
			return def;

		var val = element.getAttribute(attribute);
		if( val.isBlank() && !val.isEmpty()) // If the value is whitespace but not empty return it
			return val;
		return val.trim(); //trim spaces around the val

	}
	/**
	 * Get the optional path value of a node from the given element with the given name
	 *
	 * @param element The element to look in
	 * @param attribute The name of the attribute
	 * @param workPath The value to return if the node wasn't found
	 * @return The requested path or an empty optional is something went wrong
	 */
	public static Optional<Path> getPathAttribute(Element element, String attribute, Path workPath ) {
		if( element == null ){
			Logger.error("Parent is null when looking for "+attribute);
			return Optional.empty();
		}
		if( !element.hasAttribute(attribute))
			return Optional.empty();

		String p = element.getAttribute(attribute).trim().replace("/", File.separator); // Make sure to use correct slashes
		p = p .replace("\\",File.separator);
		if( p.isEmpty() )
			return Optional.empty();
		var path = Path.of(p);

		if( path.isAbsolute() || workPath==null)
			return Optional.of(path);
		return Optional.of( workPath.resolve(path) );
	}
	/* **************************** E L E M E N T   V A L U E S ***************************/
	/* ********************************* W R I T I N G **************************************/
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
			Logger.error(e);
			return Optional.empty();
		}
	}

	/**
	 * Create a child node in the given parent with the given text content
	 * @param xmlDoc The document which the parent belongs to
	 * @param parent The parent node
	 * @param node The name of the child node
	 * @return The created node if succesfull or null if failed
	 */
	public static Optional<Element> createChildTextElement( Document xmlDoc, Element parent, String node, String content ){
		
		if( xmlDoc==null || parent == null){
			Logger.error("Given parent or doc is null while looking for "+node);
			return Optional.empty();
		}

		try{			
			Element ele = xmlDoc.createElement(node);
			ele.appendChild( xmlDoc.createTextNode(content) );
			parent.appendChild(ele);
			return Optional.of(ele);
		}catch( DOMException e){
			Logger.error(e);
			return Optional.empty();
		}		
	}
}
