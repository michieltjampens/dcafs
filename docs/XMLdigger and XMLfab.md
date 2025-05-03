# XMLdigger & XMLfab

A lot of dcafs relies on XML for setting up etc, so a lot of code interacting with those files is used.
Because a lot of those things are repeated, three utility classes were made.

Originally there was 'XMLtools', which provided the basic interface to interact with elements.  
But because this still required a lot of boilerplate when building an XML, the XMLfab was created.

This class is geared towards 'fabricating' an XML doc from scratch and relies on the earlier mentioned 'XMLtools'.  
XMLfab thus was best suited to create and XML not read it, to fix this the XMLdigger was made.

XMLdigger specialises in 'digging' through the XML trying to get to the point where the XMLfab can take over.  
Because it's most often dig then fab, digger will be explained first.

Yes, i know that digging is the opposite of the idea that the xml is a 'tree' that starts at a root.
I just liked the idea/word more than climbing.

## XMLdigger

As mentioned earlier, this is used to go through an xml to get to the point to start the fab.

There are two ways to get started.
````java
    // Either start from an xml document and dig to the rootnode
    var dig = XMLdigger.goIn( Path xml, String rootnode);
    // or from a given element inside a doc
    var dig = XMLdigger.goIn( Element element);
````
From that point, there are two options. Either dig down or peek in the hole.  

### Basic digging
Available methods:
- `digDown( String... tag )` dig at the current location down these tags, in order
- `digDown( String tag, String attr, String value)` dig if matching the attr&val 
- `digDown( String tag, String content)` dig if matching content

Digging won't cause any errors to be thrown because the digger doesn't actually dig if the previous operation failed.  
It's up to the user to check if the digging was successful.
- `isValid()` Checks if the digging so far is valid
- `isInvalid()` or the opposite  
This also means, that the moment the digging fails the digger becomes invalid.

To explain this, the content of example.xml
````xml
<root>
    <subnode>
        <subber></subber>
    </subnode>
</root>
````
````java
    // First initialise a digger
    var digger = XMLdigger.goIn( Path.of("example.xml"), "root");
    var ok = digger.isValid(); // ok would be true at this point
    digger.digDown("subnode"); // valid
    digger.digDown("hole"); // invalid
    digger.digDown("anotherhole"); // already invalid, don't bother 
    
    // Or the same operation but shorter
    var digger = XMLdigger.goIn( Path.of("example.xml"), "root");
    dig.digDown( "subnode","hole","anotherhole");
    ok = digger.isValid(); // ok would false
            
    // Or actually valid digging
    var digger = XMLdigger.goIn( Path.of("example.xml"), "root");
            digger.digDown( "subnode","subber");
    ok = dig.isValid(); // ok would be ok
````
After digging, the digger is point to a certain element in the XML.

### Basic peeking

Peeking is slightly different to digging.  
- Peeking stays at the same level, so you can peek for multiple tags 
- Peeking doesn't invalidate the digger
- Peeking results in a pointer to another element inside the digger

So this means we just 'peek' in the hole the digger made. 
- `peekAt(String tag)`  
- `peekAt(String tag, String attr, String value)`
- `peekAt(String tag, String content)`
This can result in either a valid or invalid pointer. To check this, use `hasValidPeek`.  

Because the above is often used in if/else structures
- `hasPeek( String tag) `
- `hasPeek( String tag, String attr, String value )`
Are the same as first `peekAt` followed by `hasValidPeek`.

The reason the distinction is made between 'peek' and 'dig' is because the effect both operations
have on the object and what the next possible steps are.

It's possible to dig to the peek with `usePeek`, this will invalidate the digger if the peek failed.

## XMLfab

This class predates the XMLdigger so there's a bit of overlap.  
The main difference between the digger and the fab, is that the latter doesn't care if something
is present or not. If it isn't, it will be made.

````java 
import util.xml.XMLfab;

class XMLTest {
    public XMLTest {
        var fab = XMLfab.withRoot(Path.of("settings.xml"),"dcafs");
        if( fab.isInvalid() ) {
            // The only reason this is reached, is if the xml already exists with a different
            // rootnode
        }
        fab.build(); // Will create the xml and give it the that root node
    }
}
````
### Digging around

Now that the basics are explained, next up is some actual practical examples.
````xml
<!-- content of settings.xml -->
<dcafs>
    <streams>
        <stream id="tcpthing">
            <addres>localhost:4200</addres>
        </stream>
        <stream id="another">
            <addres>localhost:4011</addres>
        </stream>
    </streams>
</dcafs>
````
So consider we'd like to add an element to the stream with id 'another'.
````java
class Example {
    public boolean addAnother() {
        var dig = XMLdigger.goIn(Path.of("settings.xml"), "dcafs");
        dig.digDown("streams");
        if (dig.isValid()) { // Streams element exists
            if (dig.hasPeek("stream", "id", "another")) {
                dig.usePeek();
                
            } 
            return false;
            
        }
    }
}
````