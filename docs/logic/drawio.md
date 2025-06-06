# dcafs Draw.io Diagram Parsing & Automation Summary

## 1. General Concept
- dcafs converts **Draw.io diagrams** directly into automation logic, no intermediary file is made or used.
- Diagrams → blocks (shapes) + links (arrows).
- Visual layout is ignored except for IDs and properties.
- Transforms complex, often difficult-to-read text-based automation logic into intuitive, visually-driven flowcharts.

## 2. File Input & Parsing
- Uncompressed `.drawio` XML files imported directly.
- This direct parsing is feasible due to Draw.io's robust XML structure, which semantically represents shapes as unique nodes and 
connections as defined edges, allowing logical extraction independent of visual layout specifics. This inherently supports meaningful 
version control and diffing of logical changes, facilitating collaborative development and tracking.
- Page boundaries are ignored (user can split logic visually, but parser treats as one).
- Each shape with a valid `dcafstype` property is parsed; this property defines the functional type of the block (e.g., 
`readerblock`, `emailblock`, `conditionblock`). Invalid/missing type → ignored
- The parser generates `dcafsid`s based on block position and routing path — not the Draw.io ID (but Draw.io ID remains available for reference).
- Arrows link blocks via their source/target shapes. Arrow labels (or `dcafslabel` property) determine the type of connection (e.g., 'ok', 'fail', 'next').
- Labels are normalized to lowercase to avoid ambiguity.

## 3. IDs & Routing
- The parser generates dcafsids based on block position and routing path (e.g., `blocktask@0|[0][3]|0|0`), which 
encodes both its unique identity and its place within the execution flow/ancestry.
- Circular references/loops are allowed and handled correctly during execution, not flagged as errors 
(e.g., for retry mechanisms or continuous monitoring).
- Dangling arrows (links pointing to nonexistent blocks) generate warnings in logs.

## 4. Links & Labels
- Arrow labels determine link meaning, explicitly defining the **route followed** between blocks (e.g., `retry`, `fail`, `next`).
Synonyms are allowed (e.g., `next` can have `ok` or `pass`).
- No multi-line labels on arrows.
- Visual label on arrows take precedence over `dcafslabel` property for link meaning.

## 5. Error Handling & Ignored Elements
- Shapes or arrows with unknown or invalid types produce errors/warnings or are ignored.
- Shapes without a `dcafstype` property present are ignored.
- Comments can be added by using arrows labeled `?` or text fields. These elements are purely for documentation and are safely ignored by the parser.

## 6. Standard Library & Macros
- A standard library of prebuilt blocks with attached arrows exists, complete with labels and properties, serving as 
reusable components to reduce user errors and speed diagram construction.
- All block and label names are lowercased before parsing.
- Macros for external configs or reusability are planned but not in short term.

## 7. Extensibility
- Adding new blocks that require Java code is considered acceptable and normal.
- The parsing logic for new block types involves adding cases to a switch and implementing a method in Java.
- No external connections for now, can only use what already present in dcafs.

## 8. Dry Run / Simulation
- A dry-run functionality is built in: it walks through blocks following routes and lets each block append info to a string.
- Prevents infinite loops/cycles in traversal.
- Indentation is used to show alternative routes clearly.
- Output is textual and gives a route summary.
- Large diagrams may produce a lot of output; scaling the dry run with better formatting or partial traversal is an open point.

## 9. Logging & Debugging
- Dangling or malformed links produce warnings in logs.
- Errors are only given when something **should** be recognized (e.g., missing `dcafstype`).
- Route-based IDs aid in finding blocks programmatically and tracking route execution.

## 10. User Interaction & Future Features
- Users can decorate diagrams however they want without affecting execution logic, reinforcing its dual role as a configuration and documentation tool.
- No “test mode” or preview mode is planned beyond dry run, as the dry run already acts as logic verification.
- Comments and non-parsed elements can co-exist in diagrams for documentation purposes.

## 11. Runtime Reloading
- The parser supports auto-reload, meaning it can detect and re-parse changes to the diagram configuration at runtime.
- However, hotplugging (changing a task or block while it’s running) is not currently supported.
- Tasks must be stopped and restarted to reflect changes, but the architecture does not inherently prevent future support for
live patching, should the need arise.

