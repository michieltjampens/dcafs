# Dynamic Units

If the values read have a wide range this isn't handy to read. So dynamic units were added so the values that
you see (but not those stored) are scale.

Below is a full example with explanation.

```xml

<rtvals>
    <!-- Step based, so if starting with seconds, once this reaches 60 it's displayed as 1m then 1m1s and so on -->
    <unit base="s">
        <step cnt="60">m</step>
        <step cnt="60">h</step>
    </unit>
    <!-- Level based -->
    <!-- From one level to the next the division is 1000 -->
    <!-- This is applied the moment the value passes the set max -->
    <!-- So 1001uA will become 1.001mA but scale is set to two, so 1mA -->
    <unit base="A" div="1000">
        <level max="1000">uA</level>
        <level max="1500" scale="2">mA</level>
        <level>A</level>
    </unit>
</rtvals>
```