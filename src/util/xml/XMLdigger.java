package util.xml;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.tools.Tools;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Class that can be used to 'dig' through a XML structure for a certain element and get info from it.
 * This won't make elements or attributes, that's what the XMLfab class is for
 */
public class XMLdigger {

    boolean valid = true;
    Path xmlPath;
    Document xmlDoc;        // The xml document
    Element root;
    Element last;
    Element peek;
    boolean peeked=false;
    ArrayList<Element> siblings = new ArrayList<>();

    private XMLdigger( Path xml ){
        xmlPath=xml;
        XMLtools.readXML(xml).ifPresentOrElse( d -> xmlDoc=d,()->invalidate());
    }
    private XMLdigger( Element cur ){
        if( cur == null){
            valid=false;
        }else {
            last = cur;
            root = (Element) cur.getParentNode();
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
    public Document doc(){
        return xmlDoc;
    }
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
    public XMLdigger digDown(String tag, String attr, String value ){
        if(!valid)
            return this;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getAttribute(attr).equalsIgnoreCase(value)).findFirst();

        eleOpt.ifPresentOrElse(this::stepDown, this::invalidate);
        siblings.clear();
        return this;
    }
    public XMLdigger digDown(String tag, String content ){
        if(!valid)
            return this;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getTextContent().equalsIgnoreCase(content)).findFirst();
        eleOpt.ifPresentOrElse(this::stepDown, this::invalidate);
        siblings.clear();
        return this;
    }

    public XMLdigger peekAt(String tag ){
        peeked=true;
        peek = XMLtools.getFirstChildByTag(last,tag).orElse(null);
        return this;
    }
    public XMLdigger peekAt(String tag, String attr, String value ){
        peeked=true;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getAttribute(attr).equalsIgnoreCase(value)).findFirst();
        peek=eleOpt.orElse(null);
        return this;
    }
    public boolean hasValidPeek(){ return peeked && peek!=null; }
    public boolean peekAtContent( String tag, String content ){
        peeked=true;
        var eleOpt = XMLtools.getChildElements(last==null?root:last, tag).stream().filter(x ->
                x.getTextContent().equalsIgnoreCase(content)).findFirst();
        peek=eleOpt.orElse(null);
        return hasValidPeek();
    }
    public boolean hasPeek(String tag ){
        peekAt(tag);
        return hasValidPeek();
    }
    public Optional<Element> peekAndUse( String tag ){
        peekAt(tag);
        return hasValidPeek()?Optional.of(peek):Optional.empty();
    }
    public boolean hasPeek(String tag, String attr, String value ){
        peekAt(tag,attr,value);
        return hasValidPeek();
    }

    public XMLdigger usePeek(){
        last=peek;
        valid = last!=null;
        return this;
    }
    public XMLdigger goUp(){
        last = root;
        peeked=false;

        var parent = (Element) root.getParentNode();
        if(  validated(parent!=null) ) {
            root = (Element)root.getParentNode();
        }
        return this;
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
    public boolean isValid(){
        return valid;
    }
    public boolean isInvalid(){
        return !valid;
    }

    public Optional<Element> current(){
        return valid?Optional.of(last):Optional.empty();
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
    public XMLdigger toLastSibling(){
        if( !siblings.isEmpty() ) {
            last = siblings.get(siblings.size() - 1);
        }else{
            invalidate();
        }
        return this;
    }

    /**
     * Get a list of all elements that result from digging to the tag
     * @param tag The tag to dig for
     * @return The elements found or an empty list if none or invalid digger
     */
    public ArrayList<Element> digOut( String tag ){
        var temp = new ArrayList<Element>();
        if( !valid )
            return temp;
        digDown(tag);
        temp.addAll(siblings);
        siblings.clear();
        return temp;
    }
    /**
     * Get a list of all elements that result from peeking to the tag.
     * Difference with digOut is that this doesn't invalidate the peek on failure
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

    public String value( String def){
        if( !valid )
            return def;

        if( peeked )
            return peek==null?def:peek.getTextContent();

        var c = last.getTextContent();
        return c.isEmpty()?def:c;

    }
    public int value( int def ){
        if( !valid )
            return def;

        if( peeked )
            return NumberUtils.toInt( peek!=null?peek.getTextContent():"",def );
        return NumberUtils.toInt(last.getTextContent(),def);
    }
    public double value( double def ){
        if( !valid )
            return def;

        if( peeked )
            return NumberUtils.toDouble( peek!=null?peek.getTextContent():"",def );

        return NumberUtils.toDouble(last.getTextContent(),def);
    }
    public boolean value( boolean def ){
        if( !valid )
            return def;

        if( peeked )
            return Tools.parseBool( peek!=null?peek.getTextContent():"",def );
        return Tools.parseBool(last.getTextContent(),def);
    }
    public Optional<Path> value( Path parent ){
        if( !valid )
            return Optional.empty();

        String at = "";
        if( peeked ) {
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
    /*  ************ Getting attributes ************************************* */
    public String attr( String tag, String def){
        if( !valid )
            return def;
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                return peek.getAttribute(tag);
        }else if( last.hasAttribute(tag)) {
            return last.getAttribute(tag);
        }
        return def;
    }
    public Optional<Path> attr( String tag, Path def, Path workpath){
        if( !valid ) { // If the digger isn't valid
            return def==null?Optional.empty():Optional.of(def);
        }
        Optional<Path> p = Optional.empty();
        if( peeked ){
            if( peek!=null && peek.hasAttribute(tag))
                p=XMLtools.getPathAttribute(peek,tag,workpath);
        }else if( last.hasAttribute(tag)) {
            p=XMLtools.getPathAttribute(last,tag,workpath);
        }
        if( p.isEmpty() && def!=null ) // if no path found, but valid default given
            return Optional.of(def);
        return p;
    }
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
        if( valid && root.hasAttribute(tag)) {
            var at = root.getAttribute(tag);
            var p = Path.of(at);
            if( p.isAbsolute() ) {
                return Optional.of(p);
            }else{
                return Optional.of(parent.resolve(at));
            }
        }
        return Optional.empty();
    }
}
