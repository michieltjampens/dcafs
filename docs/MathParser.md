# Mathematical Parser

The aim of this document is to explain how the parsing of mathematical equations
is done in dcafs.

## What you need to know before

- What the rtvals are.

## From String to functions.

For the sake of this document, let's assume the equation is:  
`i0=((i1-3)*(i3+5*2))/({group_name}+10*(i1-3))`
This contains:

- References to received data: i0,i1 and so on.
- References to realtimevalues: {group_name}

### Checks

Before any parsing is done, some things are checked and altered.

- Check if the amount of brackets is even and in the right order.
- Add brackets around the right side if none are present yet.
- Replace things like `i0++` and `io*=4` to full equations.

### 1. Replacing references

To hold all the data generated in this step, we'll use:

- An arraylist<Integer> called references
- An arraylist<NummericVal> for rtvals called valRefs

To make parsing easier, we'll normalize the references on the right side of the equation.  
`((i1-3)*(i3+5*2))/({group_name}+10*(i1-3))`
Instead of a reference to different sources, we'll refer to position in the new
arraylist `references` instead. The `i` will be retained to show it's an input.

1. Go through the expression and replace any `i*` style reference with an `i` followed by
   the index in the `references` list.

* `i1` becomes `i0` and 1 is added to references
* `i3` becomes `i1` and 3 is added to references
* Because of this `references` looks like this [1][3]

2. Look for rtval references, same process except add an offset of 100 to track that it refers
   to the `valRefs` instead.

* Check if `rtval_ref` is present in the global rtvals collection, if not abort.
* Add the val to the local `valRefs` and add the index +100 to `references`.
* Replace `{group_name}` with `i2` because we used index 2 in `references`.
* Now `references` holds [1][3][100]

After this, the expression becomes:  
`((i0-3)*(i1+5*2))/(i2+10*(i0-3))`

To safe some space, both ArrayLists are now converted to Arrays.

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
For example, `(i1+5*2)` which became `[i1][+][5][*][2]`.

* Following the order of operands, the first group found is `[5][*][2]`
    * This can be calculated directly so gets replaced with 10 instead. Meaning `[i1][+][5][*][2]` became `[i1][+][10]`.
* Next the size of `parts` is now three, so reorder the remaining elements `[i1][10][+]` and add to `results`.

For example, `(i1+5-2*i3)` which became `[i1][+][5][-][2][*][i3]`.

* Following the order of operands, the first group found is `[2][*][i3]`
    * This can't be calculated, so:
        * Reorder to `[2][i3][*]`
        * Add this to `results`
        * Replace the group in `parts` with `[o3]`, to get `[i1][+][5][-][o3]`
* Following the order of operands, the next group found is `[i1][+][5]`
    * This can't be calculated, so:
        * Reorder to `[i1][5][+]`
        * Add this to `results`
        * Replace the group in `parts` with `[o4]`, to get `[i1][+][o4]`
* Now the size of `parts` is three, so reorder the remaining elements `[i1][o4][+]` and add to results.
* After processing `results` is return to the second step **2. Splitting in sub expressions**.

### 4. Convert groups to lambda's

The final step in the parsing process. This uses an `ArrayList<Function<BigDecimal[], BigDecimal>>`
called `steps` to store the lambda's.

Now the elements in `subExpr` are iterated over one by one. A huge switch statement is used to convert
such element to a Function that receives a `BigDecimal[]` and gets the resulting `BigDecimal` in return.

This is done by generating a function that executes the operand of the group on the value/ref parts.
Value will become a hardcoded constant and ref refers to the index in the received `BigDecimal[]`.

### 5. The left side of the equation

This will instruct on what to do with the solution of the equation. There are three options:

- An **index referring to the received data**, with the already known format of `i*`.
- A **reference to a rtval** in the format `{group_name}`.
- A **temporary storage** that will be used for intermediate results with the format `t*`

We'll use the same rules as the conversions in step **1. Replacing references** annd store the result in an
`Integer` called `resultIndex`.

#### Index referring to the received data

The `i` is removed and the remainder is parsed and written to `resultIndex`

#### Reference to a rtval

The global `rtvals` collection is searched for a match:

- **If not found**, the parsing is aborted.
- **If found**, it's added to `valRefs` and the index +100 is stored in `resultIndex`

#### Temporary storage

This is only relevant if the equation isn't singular (like multiple in succession in `MathForward`).
Because in that case `valRefs` are shared, a rtval is created with id `dcafs_t*` and added to `valRefs`.
The index of `dcafs_t*` gets 100 added to it and stored in `resultIndex`.

### 6. Passing it on.

With this the parsing is complete and all required info is given to the class that uses is.

- The `ArrayList<Function<BigDecimal[], BigDecimal>>` called steps holding the lambda's.
- An `Integer[]` called `references`, that maps `i*` to the source
- A `NummericVal[]` holding the rtvals called `valRefs`
- A `String` called `expression` holding the original expression for debug purposes.
- An `Integer` called `resultIndex` holding the index to which the result of it all is written.

Based on this info, some extras are determined.

- An `Integer` called highestI, holds the highest `i*` index used.
- A `BigDecimal[]` called `scratchpad` that will be given to the lambda's. The length of this array, is
  equal to the length of `steps` and `references` combined.

## From functions to solution