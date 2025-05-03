package util.xml;

import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;

public class XmlMiniFab {
    private Element last;           // The last created/added element
    final private Document xmlDoc;        // The xml document
    private Path xmlPath;           // The path to the xml document

    XmlMiniFab(Document ori, Element work) {
        xmlDoc = ori;
        last = work;
    }

    public boolean isInvalid() {
        return xmlDoc == null;
    }

    /**
     * Rename the tag of the current parent
     *
     * @param tag The new tag
     * @return This fab after altering the tag
     */
    public XmlMiniFab renameTag(String tag) {
        xmlDoc.renameNode(last, null, tag);
        return this;
    }

    /**
     * Add a child node to the current parent
     *
     * @param tag The tag of the child to add
     * @return The fab after adding the child node
     */
    public XmlMiniFab addChild(String tag) {
        addChild(tag, "");
        return this;
    }

    public XmlMiniFab addAndAlterChild(String tag) {
        if (tag.contains("?")) {
            Logger.error("Node Tag names can't contain '?'.");
            return this;
        }
        last = XMLtools.createChildTextElement(xmlDoc, last, tag, "").orElse(last);
        return this;
    }

    /**
     * Add a child node to the current parent node with the given content
     *
     * @param tag     The tag of the child node to add
     * @param content The content of the child node to add
     * @return The fab after adding the child node
     */
    public XmlMiniFab addChild(String tag, String content) {
        if (tag.contains("?")) {
            Logger.error("Node Tag names can't contain '?'.");
            return this;
        }
        XMLtools.createChildTextElement(xmlDoc, last, tag, content);
        return this;
    }

    public XmlMiniFab addSibling(String tag) {
        if (tag.contains("?")) {
            Logger.error("Node Tag names can't contain '?'.");
            return this;
        }
        last = XMLtools.createChildElement(xmlDoc, (Element) last.getParentNode(), tag).orElse(last);
        return this;
    }

    /**
     * Remove all the children of the parent node
     *
     * @return The fab after removing the child nodes of the current parent node
     */
    public XmlMiniFab clearChildren() {
        return clearChildren("");
    }

    /**
     * Remove all children with a specified tag, or empty/* for all
     *
     * @param tag The tag to remove (or empty for all)
     * @return This fab after removing childnodes
     */
    public XmlMiniFab clearChildren(String tag) {
        if (tag.isEmpty() || tag.equalsIgnoreCase("*")) {
            var parent = (Element) last.getParentNode();
            if (parent == null) {
                Logger.error("Given node is null");
            } else {
                while (parent.hasChildNodes()) {
                    parent.removeChild(parent.getFirstChild());
                }
            }
        }
        return this;
    }

    /**
     * Remove a single child node from the current parent node
     *
     * @return This fab
     */
    public XmlMiniFab removeNode() {
        var parent = last.getParentNode();
        parent.removeChild(last);
        last = (Element) parent;
        return this;
    }

    /**
     * Add a comment as a child node to the current node
     *
     * @param comment The comment to add
     * @return The fab after adding the comment
     */
    public XmlMiniFab comment(String comment) {
        last.appendChild(xmlDoc.createComment(" " + comment + " "));
        return this;
    }

    /**
     * Add a comment above the current node (meaning on top instead of inside
     *
     * @param comment The comment to add
     * @return The fab after adding the comment
     */
    public XmlMiniFab commentBack(String comment) {
        last.getParentNode().insertBefore(xmlDoc.createComment(" " + comment + " "), last);
        return this;
    }
    /* Attributes */

    /**
     * Add an attribute with the given value
     *
     * @param attr  The attribute to add
     * @param value The value for this attribute
     * @return The fab after adding the attribute
     */
    public XmlMiniFab attr(String attr, String value) {
        last.setAttribute(attr, value);
        return this;
    }

    /**
     * Add an attribute with the given value
     *
     * @param attr  The attribute to add
     * @param value The value for this attribute
     * @return The fab after adding the attribute
     */
    public XmlMiniFab attr(String attr, int value) {
        last.setAttribute(attr, String.valueOf(value));
        return this;
    }

    /**
     * Add a double attribute with the given value
     *
     * @param attr  The attribute to add
     * @param value The value for this attribute
     * @return The fab after adding the attribute
     */
    public XmlMiniFab attr(String attr, double value) {
        last.setAttribute(attr, String.valueOf(value));
        return this;
    }

    /**
     * Add an empty attribute to the current node
     *
     * @param attr The name of the attribute
     * @return The fab after adding the attribute
     */
    public XmlMiniFab attr(String attr) {
        last.setAttribute(attr, "");
        return this;
    }

    /**
     * Remove an attribute of the current node
     *
     * @param attr The name of the attribute to remove
     */
    public void removeAttr(String attr) {
        if (last.hasAttribute(attr))
            last.removeAttribute(attr);
    }
    /* Content */

    /**
     * Set the content of the current node
     *
     * @param content The new content
     * @return The fab after setting the content
     */
    public XmlMiniFab content(String content) {
        last.setTextContent(content);
        return this;
    }


    /**
     * Build the document based on the fab
     *
     * @return The build document or null if failed
     */
    public boolean build() {
        if (xmlPath == null) {
            XMLtools.updateXML(xmlDoc);
        } else {
            XMLtools.writeXML(xmlPath, xmlDoc);
        }
        return xmlDoc != null;
    }

    public XMLdigger useDigger() {
        return XMLdigger.goIn(xmlDoc, last);
    }

    public XmlMiniFab goUp() {
        last = (Element) last.getParentNode();
        return this;
    }
}