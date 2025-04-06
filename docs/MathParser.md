# Mathematical Parser

The aim of this document is to explain how the parsing of mathematical equations
is done in dcafs.

## Good to know before reading

- That the [rtvals](rtvals.md) are the way dcafs stores realtime data. With `NumericVal` being an interface for the
  ones that are numeric.
- ArrayList holds objects; StringBuilder builds text — both grow as needed.

## From String to functions.

For the sake of this document, let's assume the equation is:  
`i0=((i1-3)*(i3+5*2))/({group_name}+10*(i1-3))`  
This contains:
- References to the **array** containing the received data: i0,i1 and so on.
    - On the left side of `=`, the **target** of the solution. Thus, in this case, `i0` will be assigned the solution.
      - On the right side of `=`, the **expression**. Here references are instead replaced with the current value.
- References to **realtimevalues** aka rtvals: {group_name}.  
  (This could also be the target.)

### Checks

Before any parsing is done, some things are checked and altered.

- That the amount of brackets is even, and they are the right sequence.
- Add brackets around the expression, if none are present yet.
- Replace things like `i0++` and `io*=4` to full equations e.g. `i0=i0+1` and `i0=i0*4`.

### 1. Replacing references

To hold all the data generated in this step, we'll use:

- An `Arraylist<Integer>` called `references`
- An `Arraylist<NummericVal>` for rtvals called `valRefs`
- An `Integer` called `highestI`
- An optional `Integer` called `resultIndex`

To make parsing easier, we'll normalize the references in the expression.  
`((i1-3)*(i3+5*2))/({group_name}+10*(i1-3))`  
Instead of references to different sources, we'll refer to positions in the new
`references` ArrayList.  
The `i` will be retained to reflect it's inserted.

1. **Replace current i references**
    * Use regex to collect them. In this case, it finds `[i1,i3]`
    * Sort the results. (e.g. `[i1,i3]` remains the same).
    * Add the numeric part to `references`. (e.g. `[1,3]`)
   * Alters the expression to reflect the indexes in `references`. (e.g. change `i1` to `r0`)
   * The maximum value found in `references` is stored in `highestI`, this will be used to check if the received
     data (potentially after splitting) actually has enough items.

2. **Replace rtval references**
    * Follow the same process, but with an offset of 100 to differentiate `valRefs` from the old `i` references.
    * Ensure `group_name` exists in the global rtvals collection. If not, abort.
    * Add the rtval to the local `valRefs` and add its index + 100 to `references`.
   * Replace `{group_name}` with `r2` (since we used index 2 in `references`).
    * Now `references` holds `[1,3,100]`
   * This is repeated for every `rtval` in the expression.

After this, the expression becomes: `((r0-3)*(r1+5*2))/(r2+10*(r0-3))`

3. **Handle the target reference**  
   If an equation is being processed, handle it similarly:

- For an `i*` reference, store the numeric part in `resultIndex`.
- For a `rtval` reference, write the resulting index (with offset) in `resultIndex`.
- For a `t*` reference (temporary storage), create placeholder `rtval`, follow the same procedure as `rtval`, but
  write the index to `resultIndex` instead of `references`.

To safe space, both ArrayLists are then converted to Arrays.

### 2. Splitting in sub expressions

To hold all the data generated in this step, we'll use an `Arraylist<String[]>` called `subExpr`.

1. **Split the equation**  
   Divide the equation into two parts. The first part will contain the main expression, while
   the second part will contain the nested sub-expression(s).
2. **Traverse the second expression**  
   Move through the second part from left to right, looking for a closing bracket `)`.
3. **Find the corresponding opening bracket**  
   Once a closing bracket is found, move back to the left to find the matching opening bracket `(`.
4. **Extract**
    * Extract the part of the expression between the matching brackets.
    * Pass the extracted part to **3. Splitting sub expressions in functional parts**
        * **If the result is a single array of the form `[solution][0][+]`**, replace the extracted part in the
          expression with the solution and go back to 2.
        * **If not**, add all received arrays to `subExpr` and replace the part in the expression with `o*`
          where `*` is the index of the last element in `subExpr`  
          (The letter `o` is used to denote that it's an output.)
5. **Repeat the process**  
   Continue the process of traversing and extracting until no more brackets are found.   
   (Because of the earlier checks, the expression will be fully processed into manageable sub-expressions.)

At this point, `subExpr` contains the expression split in groups of the form `[value/ref][value/ref][operand]`.
This is used by step **4. Convert groups to lambda's**.

### 3. Splitting sub expressions in functional parts

This steps goes through a sub expression.

To hold all the data generated in this step, we'll use:

- An `Arraylist<String>` called `parts`
- A `StringBuilder` used as cache during processing

Next up is going through each part again and splitting it further.

1. **Normalize scientific notation**  
   First replace scientific notation so it no longer contains signs. This
   uses regex to find them, uppercase it and than replace `E-` with `e`.
2. **Convert to char array and iterate**  
   Convert the expression string to a char array and iterate over it character by character.
3. **Caching non-operands**  
   If the cache is empty or the current character is **not** an operand, add it to the cache.  
   (This handles a `-` at the start or after an operand.)
4. **On encountering an operand**  
   Dump the cache to `parts` and also add the operand as its own entry.
5. **Clear the cache**  
   Clear the cache for the next group of characters
6. **Repeat**  
   Go back to three and repeat till the end of the group is reached
7. **Finalize the part**  
   If the cache still contains characters at this point, add them to the arraylist. If the
   cache is empty here, the expression is faulty and parsing should abort.
8. **Post-Processing**
    - Restore scientific notation by undoing the earlier replacement (`e` back to `E-` if needed).
    - Handle the edge case of `^-`, meaning a negative exponent. If found transform the
      `parts` so that `[x,^,-y]` becomes`[1,/,x,^,y]`.   
      (The parser follows the correct order of operations, so extra brackets are not required.)

The next step works with this `parts` collection.

### 4. Splitting the functional parts in groups of three

This step involves further dividing the `String` elements in groups of two values/refs and an operand.
For this an `ArrayList<String[]>` called `results` is used to store the groups. The order of the elements is altered
so that it's `[value/ref][value/ref][operand]` instead of `[value/ref][operand][value/ref]` (forgot why).

#### Case 1: Size of parts is three

1. **Check if it can be calculated**  
   If the group of three elements can be directly calculated  (i.e., it's a simple expression like
   `[value][operand][value]`), do the following:
    - Replace the expression with the result and add `[result][0][+]` to `results`.  
      (this indicates to the next step that this expression can be simplified).
    - If it can't be calculated, reorder the elements and add them to `results`.
2. **Pass to the next step**  
   After processing, pass `results` back to the second step **2. Splitting in sub expressions**.

#### Case 2: Size of parts is greater

To split `parts` further in groups of three:

1. **Traverse the array**  
   Go from left to right to look for the operand of the highest order, if not found the next one and so on.
2. **Operand found**  
   Check if this can be calculated:
    * **If it can be calculated**, replace the operand and neighbouring values with the result.
    * **If it can't be calculated**, a reorder the elements into the form `[value][value][operand]`
      and add the group to `results`. Then, replace the group in parts with `o*`   
      (where `*` is the next available index, as used in step 2).
    * **If the size of  `parts` is now 1**, the processing of `parts` is complete.
3. **Repeat**  
   Continue processing:
    * If another operand is found, go back to step 2.
    * If the end is reached and no more operands are found, return to step 1 unless all operands have been processed.
      Once all operands have been accounted for, the process is complete.

**Example**  
For example, `(r1+5*2)` which became `[r1][+][5][*][2]`.

* Following the order of operands, the first group found is `[5][*][2]`
    * This can be calculated directly so gets replaced with 10 instead. Meaning `[r1][+][5][*][2]` became `[r1][+][10]`.
* Next the size of `parts` is now three, so reorder the remaining elements `[r1][10][+]` and add to `results`.

For example, `(i1+5-2*i3)` which became `[r1][+][5][-][2][*][r3]`.

* Following the order of operands, the first group found is `[2][*][r3]`
    * This can't be calculated, so:
        * Reorder to `[2][r3][*]`
        * Add this to `results`
        * Replace the group in `parts` with `[o3]`, to get `[r1][+][5][-][o3]`
* Following the order of operands, the next group found is `[r1][+][5]`
    * This can't be calculated, so:
        * Reorder to `[r1][5][+]`
        * Add this to `results`
        * Replace the group in `parts` with `[o4]`, to get `[r1][+][o4]`
* Now the size of `parts` is three, so reorder the remaining elements `[r1][o4][+]` and add to results.
* After processing `results` is return to the second step **2. Splitting in sub expressions**.

### 4. Convert groups to Functions

The final step in the parsing process. This uses an `ArrayList<Function<BigDecimal[], BigDecimal>>`
called `steps` to store the lambda's.

Now the elements in `subExpr` are iterated over one by one. A huge switch statement is used to convert
such element to a Function that receives a `BigDecimal[]` and returns the solution as a `BigDecimal` .

This is done by generating a function that executes the operand of the group on the value/ref parts.  
(Value will become a hardcoded constant and ref refers to the index in the received `BigDecimal[]`).

### 5. Passing it on.

With this the parsing is complete and all required info has been gathered to compute the solution.

- The `ArrayList<Function<BigDecimal[], BigDecimal>>` called `steps` holding the Functions.
- An `Integer[]` called `references`, that maps `r*` to the source
- A `NummericVal[]` holding the rtvals called `valRefs`
- A `String` called `expression` holding the original expression for debug purposes.
- An `Integer` called `resultIndex` holding the index to which the result of it all is written.
- An `Integer` called `highestI`, holds the highest original `i*` index used.
-

Based on this info, some extras are determined.

- A `BigDecimal[]` called `scratchpad` that will be given to the Functions. The length of this array, is
  equal to the length of `steps` and `references` combined.

## From Functions to the solution

This might be easier to explain by example. 