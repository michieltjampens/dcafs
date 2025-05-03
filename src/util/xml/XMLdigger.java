package util.xml;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import util.tools.Tools;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Class that can be used to 'dig' through a XML structure for a certain element and get info from it.
 * This won't make elements or attributes, that's what the XMLfab class is for
 */
public class XMLdigger {

    private boolean valid = true;
    private Document xmlDoc;   // The xml document
    private Element firstRoot;
    private Element root;
    private Element last;
    private Element peek;
    private Element savePoint;
    private boolean peeked=false;
    private final ArrayList<Element> siblings = new ArrayList<>();

    private XMLdigger( Path xml ){
        XMLtools.readXML(xml).ifPresentOrElse( d -> xmlDoc=d, this::invalidate);
    }
    private XMLdigger( Element cur ){
        if( cur == null){
            valid=false;
        }else {
            last = cur;
            root = (Element) cur.getParentNode();
            firstRoot=root;
            root = root == null ? last : root;
        }
    }
    /**
     * Start the XML digging with selecting the first node with the given name
     * @param xml The path to the xml file
     * @param rootNode The rootnode to look for
     * @return This object
     */
    public static XMLdigger goIn(Path xml, String... rootNode ){
        var digger = new XMLdigger(xml);

        if( digger.valid ){ // If the xml file exists
            var root = (Element) digger.xmlDoc.getFirstChild(); // Get the root node
            if( root!=null && root.getTagName().equals(rootNode[0])) { // The root exists
                digger.firstRoot=root;
                digger.stepDown(root); // Go down the root
                for( int a=1;a<rootNode.length;a++) // Step down further if wanted
                    digger.digDown(rootNode[a]);
            }else{
                digger.invalidate(); // No such root, so invalid
            }
        }
        return digger;
    }
    public static XMLdigger goIn( Element ele){
        return new XMLdigger(ele);
    }

    public static XMLdigger goIn(Document xmlDoc, Element ele) {
        var dig = new XMLdigger(ele);
        dig.xmlDoc = xmlDoc;
        return dig;
    }
    public Document doc(){
        return xmlDoc;
    }

    /**
     * Try to follow successive child elements in the xml. If successfully, the digger wil be at the last element.
     * If not, it will have invalidated the digger.
     * @param tags The successive tags to follow
     * @return This - now possibly invalid - digger
     */
    public XMLdigger digDown(String... tags ){
        if(!valid)
            return this;

        siblings.clear(); // These can be cleared already

        if( tags.length!=1){
            for( int a=0;a<tags.length-1;a++) {
                XMLtools.getFirstChildByTag(root, tags[a]).ifPresentOrElse(this::stepDown, this::invalidate);
                if( !valid )
                    return this;
            }
        }

        siblings.addAll( XMLtools.getChildElements(last,tags[tags.length-1]));
        if (siblings.isEmpty()) {
            invalidate();
        } else {
            stepDown(siblings.get(0));
        }
        return this;
    }

    /**
     * Dig down (one level) to the element that matches tag,attr and value, invalidates the digger if not found.
     * @param tag The tag of the element to look for.
     * @param attr The attribute in found element to look for
     * @param value The value that must match the one of the found attribute
     * @return This - now possibly invalid - digger
     */
    public XMLdigger digDown(String tag, String attr, String value ){
        if(!valid)
            return this;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getAttribute(attr).equalsIgnoreCase(value)).findFirst();

        eleOpt.ifPresentOrElse(this::stepDown, this::invalidate);
        siblings.clear();
        return this;
    }
    /**
     * Dig down (one level) to the element that matches tag and content. Invalidates the digger if not found.
     * @param tag The tag of the element to look for.
     * @param content The content to match in found element
     * @return This - now possibly invalid - digger
     */
    public XMLdigger digDown(String tag, String content ){
        if(!valid)
            return this;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getTextContent().equalsIgnoreCase(content)).findFirst();
        eleOpt.ifPresentOrElse(this::stepDown, this::invalidate);
        siblings.clear();
        return this;
    }

    /**
     * Check if the current element contains a child with the given tag. If not found, the digger remains valid.
     * The result of the peek, can be checked with 'hasValidPeek'.
     * @param tag The tag to look for.
     * @return This digger.
     */
    public XMLdigger peekAt(String tag ){
        peeked=true;
        peek = XMLtools.getFirstChildByTag(last,tag).orElse(null);
        return this;
    }

    /**
     * Check if the current element contains a child with the given tag, attr + value. If not found, the digger remains valid.
     * The result of the peek, can be checked with 'hasValidPeek'.
     * @param tag The tag to look for.
     * @param attr The attribute to look for in the found element
     * @param value The value to match with the found attribute value
     * @return This digger.
     */
    public XMLdigger peekAt(String tag, String attr, String value ){
        peeked=true;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getAttribute(attr).equalsIgnoreCase(value)).findFirst();
        peek=eleOpt.orElse(null);
        return this;
    }

    /**
     * Check if the last peek was successful
     * @return True if peek was ok
     */
    public boolean hasValidPeek(){ return peeked && peek!=null; }

    /**
     * Check if the current element contains a child with the given tag and textcontent.
     * If not found the digger remains valid.
     * @param tag The tag to look for.
     * @param content The content to match with the found element.
     * @return True if successful.
     */
    public boolean peekAtContent( String tag, String content ){
        peeked=true;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getTextContent().equalsIgnoreCase(content)).findFirst();
        peek=eleOpt.orElse(null);
        return hasValidPeek();
    }

    /**
     * Check if the current element contains a child with the given tag, attr with the value and textcontent.
     * If not found the digger remains valid.
     *
     * @param tag     The tag to look for.
     * @param attr    The attribute to look for
     * @param attrVal The value of the attribute
     * @param content The content to match with the found element.
     * @return True if successful.
     */
    public boolean peekAtBoth(String tag, String attr, String attrVal, String content) {
        peeked = true;
        var eleOpt = XMLtools.getChildElements(last == null ? root : last, tag).stream()
                .filter(x -> x.getTextContent().equalsIgnoreCase(content)) // Find matching content
                .filter(at -> at.getAttribute(attr).equalsIgnoreCase(attrVal))
                .findFirst();
        peek = eleOpt.orElse(null);
        return hasValidPeek();
    }

    /**
     * Check if the current element contains a child with the given tag, attr with the value and textcontent.
     * If not found the digger remains valid.
     *
     * @param tag     The tag to look for.
     * @param attrVal The attribute with value to look for, multiple possible atrr1,attr1val,attr2,attr2val etc
     * @return True if successful.
     */
    public boolean peekAtMulAttr(String tag, String... attrVal) {
        if (attrVal.length % 2 == 1) {
            return false;
        }
        peeked = true;
        var stream = XMLtools.getChildElements(last == null ? root : last, tag).stream();
        int a = 0;
        while (a + 1 < attrVal.length) {
            var attr = attrVal[a];
            var val = attrVal[a + 1];
            stream = stream.filter(at -> at.getAttribute(attr).equalsIgnoreCase(val));
            a += 2;
        }
        var eleOpt = stream.findFirst();
        peek = eleOpt.orElse(null);
        return hasValidPeek();
    }
    /**
     * Check if the current element has a childnode with the given tag, returns true if so.
     * Does the same thing as peekAt(tag).hasValidPeek(), just shorter.
     * @param tag The tag to look for.
     * @return True if found.
     */
    public boolean hasPeek(String tag) {
        peekAt(tag);
        peeked = hasValidPeek();
        return peeked;
    }

    /**
     * Peek at a tag and return an optional element
     * @param tag The tag to look for
     * @return An optional containing the found element or nothing
     */
    public Optional<Element> peekAndUse( String tag ){
        peekAt(tag);
        return hasValidPeek()?Optional.of(peek):Optional.empty();
    }

    /**
     * Peeks for the given tag, attr, value combination and return true if found
     * @param tag The tag to look for
     * @param attr The attribute to look for
     * @param value The value of the attribute to look for
     * @return True if the peek was successful
     */
    public boolean hasPeek(String tag, String attr, String value ){
        peekAt(tag,attr,value);
        return hasValidPeek();
    }
    /**
     * Make the peeked element the active element, which means the following peeks/digs are going to start from it.
     * @return This object
     */
    public XMLdigger usePeek(){
        stepDown(peek);
        valid = last!=null;
        return this;
    }

    /**
     * Go back up to the parent element
     */
    public void goUp(){
        last = root;
        peeked=false;

        if( last.equals(firstRoot)) // Meaning the parent is the top root
            return;

        var parent = (Element) root.getParentNode();
        if(  validated(parent!=null) ) {
            root = (Element)root.getParentNode();
        }
    }

    /**
     * First make the digger valid again and then try to go to the parent element with the given tag
     *
     * @param tag The tag to look for
     */
    public void goUp(String tag ){
        peeked=false;
        if( root==null)
            return;
        valid=true;
        last = root;
        while( last!=null && !last.getTagName().equalsIgnoreCase(tag)) {
            var parent = (Element) root.getParentNode();
            if (validated(parent != null)) {
                root = parent;
                last=root;
            }else{
                valid=false;
            }
        }
        if( root==null) {
            return;
        }
        var parent = (Element) root.getParentNode();
        if( parent!=null)
            root=parent;
    }

    /**
     * Attempts to move up to the first parent with the given tag.
     * Restores the original position if that parent isn't found.
     * If successful, caller is responsible for restoring the original position via restorePoint().
     *
     * @return true if the move was successful, false if restored due to failure
     */
    public boolean saveAndUpRestoreOnFail(String tag) {
        if (valid) {
            savePoint();
            goUp(tag);
            if (!valid) {
                restorePoint();
                return false;
            }
            return true;
        }
        return false;
    }
    public void savePoint() {
        savePoint = last;
    }

    public void restorePoint() {
        valid=true;
        last = savePoint;
        peeked = false;
    }
    /**
     * Force this digger to be invalid
     */
    private void invalidate(){
        valid=false;
    }

    /**
     * Check if valid and add an extra check
     * @param check The extra check
     * @return True if still valid even after the check
     */
    private boolean validated(boolean check){
        valid = valid && check;
        return valid;
    }
    private void stepDown( Element ele ){
        peeked=false;
        if( root == null) {
            root = ele;
        }else if (last !=null){
            root = last;
        }
        last = ele;
    }

    /**
     * Check if this digger is still valid. Digging to a non-existing element invalidates it.
     * @return True if valid
     */
    public boolean isValid(){
        return valid;
    }
    /**
     * Check if this digger is invalid. Digging to a non-existing element invalidates it.
     * @return True if invalid
     */
    public boolean isInvalid(){
        return !valid;
    }

    public Optional<Element> current(){
        return valid?Optional.of(last):Optional.empty();
    }
    public Element currentTrusted(){
        return last;
    }
    /**
     * Get All child elements of the current active
     * @return List of the child elements
     */
    public List<Element> currentSubs(){
        if( !valid )
            return new ArrayList<>();
        return XMLtools.getChildElements(last);
    }

    /**
     * Check if the current node has any child nodes
     * @return True if it does
     */
    public boolean hasChilds(){
        if( !valid )
            return false;
        return !XMLtools.getChildElements(last).isEmpty();
    }

    /**
     * Iterate through the siblings found
     * @return True till no more siblings left
     */
    public boolean iterate(){
        if( !hasNext() )
            return false;

        last = siblings.remove(0);
        return true;
    }
    /**
     * Check if there's another sibling left
     * @return Optional sibling
     */
    private boolean hasNext(){
        peeked=false;
        return validated(!siblings.isEmpty());
    }
    public void toLastSibling(){
        if( !siblings.isEmpty() ) {
            last = siblings.get(siblings.size() - 1);
        }else{
            invalidate();
        }
    }

    /**
     * Get a list of all elements that result from digging to the tag, this might invalidate the digger.
     * @param tag The tag to dig for
     * @return The elements found as XMLdigger instances or an empty list if none.
     */
    public ArrayList<XMLdigger> digOut( String tag ){
        var temp = new ArrayList<XMLdigger>();
        if( !valid )
            return temp;
        digDown(tag);
        siblings.forEach(x -> temp.add(XMLdigger.goIn(xmlDoc, x)));
        return temp;
    }
    /**
     * Get a list of all elements that result from peeking to the tag.
     * Like all peek actions, this doesn't invalidate the digger
     * @param tag The tag to peek at
     * @return The elements found or an empty list if none or invalid peek
     */
    public ArrayList<Element> peekOut( String tag ){
        var temp = new ArrayList<Element>();
        if( !valid )
            return temp;
        temp.addAll(XMLtools.getChildElements(last,tag));
        return temp;
    }
    /* ************* Getting content **************************************** */

    /**
     * Get the string textcontent of either the last valid dig or last valid peek
     * @param def The value to return if invalid
     * @return The string read if any or def if not
     */
    public String value( String def){
        if( !valid )
            return def;

        if( peeked ) {
            peeked=false;
            return peek == null ? def : peek.getTextContent();
        }
        var c = last.getTextContent();
        return c.isEmpty()?def:c;

    }
    /**
     * Get the integer textcontent of either the last valid dig or last valid peek
     * @param def The value to return if invalid
     * @return The integer read if any or def if not
     */
    public int value( int def ){
        if( !valid )
            return def;

        if( peeked ) {
            peeked=false;
            return NumberUtils.toInt(peek != null ? peek.getTextContent() : "", def);
        }
        return NumberUtils.toInt(last.getTextContent(),def);
    }
    /**
     * Get the double textcontent of either the last valid dig or last valid peek
     * @param def The value to return if invalid
     * @return The double read if any or def if not
     */
    public double value( double def ){
        if( !valid )
            return def;

        if( peeked ) {
            peeked=false;
            return NumberUtils.toDouble(peek != null ? peek.getTextContent() : "", def);
        }
        return NumberUtils.toDouble(last.getTextContent(),def);
    }
    /**
     * Get the boolean textcontent of either the last valid dig or last valid peek
     * @param def The value to return if invalid
     * @return The boolean textcontent found if any or def if not
     */
    public boolean value( boolean def ){
        if( !valid )
            return def;

        if( peeked ) {
            peeked=false;
            return Tools.parseBool(peek != null ? peek.getTextContent() : "", def);
        }
        return Tools.parseBool(last.getTextContent(),def);
    }
    /**
     * Get the path textcontent of either the last valid dig or last valid peek
     * @param parent The parent folder to use if the path isn't absolute
     * @return The path read if absolute or appended to parent if not
     */
    public Optional<Path> value( Path parent ){
        if( !valid )
            return Optional.empty();

        String at = "";
        if( peeked ) {
            peeked=false;
            at = peek == null ? "" : peek.getTextContent();
        }else{
            at = last.getTextContent();
        }
        if( at.isEmpty() )
            return Optional.empty();

        var p = Path.of(at);
        if( p.isAbsolute() ) {
            return Optional.of(p);
        }else{
            return Optional.of(parent.resolve(at));
        }
    }
    /* ****** */

    /**
     * Get the tagname of the current active element
     * @param def The tagname to return if not valid
     * @return The tagname read or def if none
     */
    public String tagName(String def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null)
                return peek.getTagName();
        }else{
            return last.getTagName();
        }
        return def;
    }
    public boolean hasTagName(String tag){
        if( !valid )
            return false;
        if( peeked ){
            if( peek!=null)
                return peek.getTagName().equals(tag);
        }else{
            return last.getTagName().equals(tag);
        }
        return false;
    }
    /*  ************ Getting attributes ************************************* */
    /**
     * Read the value of the given tag, return the def string if not found
     * @param tag The tag to look for
     * @param def The string to return if not found
     * @return The value of the tag or def if not found
     */
    public String attr( String tag, String def){
        if (!valid || tag.isEmpty())
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return peek.getAttribute(tag);
        }else if( last.hasAttribute(tag)) {
            return last.getAttribute(tag);
        }
        return def;
    }

    /**
     * Read the value of the given tag, return the def string if not found
     *
     * @param tag The tag to look for
     * @param def The string to return if not found
     * @return The value of the tag or def if not found
     */
    public String parentAttr(String tag, String def) {
        if (root.hasAttribute("tag"))
            return root.getAttribute("tag");
        return def;
    }
    /**
     * Check if the given attribute is present
     * @param tag The attribute to look for
     * @return True if found
     */
    public boolean hasAttr( String tag){
        if( !valid )
            return false;
        if( peeked )
            return peek!=null && peek.hasAttribute(tag);
        return last.hasAttribute(tag);
    }

    public boolean noAttr(String tag) {
        return !hasAttr(tag);
    }

    /**
     * Look for the first match of the list of attributes
     * @param attr One or more attributes to look for
     * @return The matching attribute or an empty string if none found
     */
    public String matchAttr( String... attr ){
        if( !valid )
            return "";
        if( peeked ){
            if( peek ==null)
                return "";
            for( var at :attr ){
                if(peek.hasAttribute(at))
                    return at;
            }
            return "";
        }
        for( var at :attr ){
            if(last.hasAttribute(at))
                return at;
        }
        return "";
    }
    public String allAttr(){
        if( !valid )
            return "";
        NamedNodeMap atts;
        if( peeked ){
            atts = peek.getAttributes();
        }else{
            atts = last.getAttributes();
        }
        if( atts.getLength()==0 ) // Check if any attributes
            return "";
        var join = new StringJoiner(",");
        for( int a =0;a<atts.getLength();a++)
            join.add( atts.item(a).getNodeName() );
        return join.toString();
    }
    /**
     * Read the value of the given tag, return the def string if not found
     * @param tag The tag to look for
     * @param def The string to return if not found
     * @param esc If True then found escape code will be converted to bytes (fe. \r )
     * @return The content of the tag with possibly altered escape codes
     */
    public String attr( String tag, String def, boolean esc){
        var val = attr(tag,def);
        if(esc)
            val=Tools.fromEscapedStringToBytes(val);
        return val;
    }
    public Optional<Path> attr( String tag, Path def, Path workpath){
        if( !valid ) { // If the digger isn't valid
            return def==null?Optional.empty():Optional.of(def);
        }
        Optional<Path> p = Optional.empty();
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                p = getPathAttribute(peek, tag, workpath);
        }else if( last.hasAttribute(tag)) {
            p = getPathAttribute(last, tag, workpath);
        }
        if( p.isEmpty() && def!=null ) // if no path found, but valid default given
            return Optional.of(def);
        return p;
    }
    /**
     * Get the optional path value of a node from the given element with the given name
     *
     * @param element The element to look in
     * @param attribute The name of the attribute
     * @param workPath The value to return if the node wasn't found
     * @return The requested path or an empty optional is something went wrong
     */
    public static Optional<Path> getPathAttribute(Element element, String attribute, Path workPath) {
        if (element == null) {
            Logger.error("Parent is null when looking for " + attribute);
            return Optional.empty();
        }
        if (!element.hasAttribute(attribute))
            return Optional.empty();

        String p = element.getAttribute(attribute).trim().replace("/", File.separator); // Make sure to use correct slashes
        p = p.replace("\\", File.separator);
        if (p.isEmpty())
            return Optional.empty();
        var path = Path.of(p);

        if (path.isAbsolute() || workPath == null)
            return Optional.of(path);
        return Optional.of(workPath.resolve(path));
    }

    /**
     * Read the value of the given tag as an integer, return the def integer if not found
     * @param tag The tag to look for
     * @param def The integer to return if not found
     * @return The value of the tag or def if not found
     */
    public int attr( String tag, int def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return NumberUtils.toInt(peek.getAttribute(tag),def);
        }else if( last.hasAttribute(tag)) {
            return NumberUtils.toInt(last.getAttribute(tag),def);
        }
        return def;
    }
    /**
     * Read the value of the given tag as a double, return the def double if not found
     * @param tag The tag to look for
     * @param def The double to return if not found
     * @return The value of the tag or def if not found
     */
    public double attr( String tag, double def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return NumberUtils.toDouble(peek.getAttribute(tag).replace(",","."),def);
        }else if( last.hasAttribute(tag)) {
            return NumberUtils.toDouble(last.getAttribute(tag).replace(",","."),def);
        }
        return def;
    }
    /**
     * Read the value of the given tag as a boolean, return the def boolean if not found.
     * true,TRUE,1,yes will result in true, the opposites in false
     * @param tag The tag to look for
     * @param def The boolean to return if not found
     * @return The value of the tag or def if not found
     */
    public boolean attr( String tag, boolean def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return Tools.parseBool(peek.getAttribute(tag),def);
        }else if( last.hasAttribute(tag)) {
            return Tools.parseBool(last.getAttribute(tag),def);
        }
        return def;
    }

    /**
     * Get the path that is defined by the attribute, or an empty optional if no valid one was found
     * @param tag The tag that holds it
     * @param parent The parent path to make the found path absolute if it isn't yet
     * @return The path or an empty optional
     */
    public Optional<Path> attr( String tag, Path parent){
        if (!valid)
            return Optional.empty();

        String path = null;
        if (peeked) {
            if (peek != null && peek.hasAttribute(tag))
                path = peek.getAttribute(tag);
        } else if (last.hasAttribute(tag)) {
            path = last.getAttribute(tag);
        }

        if (path == null) {
            Logger.error("No attribute called " + tag + " found");
            return Optional.empty();
        }
        var p = Path.of(path);
        if (p.isAbsolute()) {
            return Optional.of(p);
        } else if (parent != null) {
            return Optional.of(parent.resolve(p));
        }
        return Optional.empty();
    }

    public XmlMiniFab useEditor() {
        return new XmlMiniFab(xmlDoc, last);
    }

}
