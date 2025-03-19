# Editor Forward Guide

This document explains the different operations applied to strings .

## 1. Replacement Operations

These operations modify parts of the string by replacing characters or substrings.

- **`<replace find="X">Y</replace>`**
    - Replaces occurrences of `X` in the string with `Y`.
    - Example: `<replace find="a">i</replace>` → "example" becomes "ixample".

- **`<regexreplace find="pattern">replacement</regexreplace>`**
    - Replaces a pattern matching a regular expression with a specified replacement.
    - Example: `find="[12]", replacement="3"` → "1, 2, 3" becomes "3, 3, 3".

- **`<replaceindex delimiter="X" index="Y">Z</replaceindex>`**
    - Replaces the element at the specified `index` when split by the `delimiter`.
    - Example: `delimiter="_", index="1", replace="Hello?"` → "12_34" becomes "12_Hello?".

## 2. Splitting and Resplitting

Operations to split the string based on a delimiter or split the string into parts for further modification.

- **`<charsplit delimiter="X">Y</charsplit>`**
    - Splits the string by the specified `delimiter` and inserts a new value `Y` at the split.
    - Example: `delimiter=",", split="5"` → "apple,banana" becomes "apple5banana".

- **`<regexsplit delimiter="X">pattern</regexsplit>`**
    - Splits the string using a regular expression to match a pattern and delimiter `X`.
    - Example: `delimiter="-", split="([a-z])"` → "Hello-world" becomes "e-l-l-o w-o-r-l-d".

- **`<resplit delimiter="X" leftover="append/remove>elements</resplit>`**
    - Resplits a string into new parts based on the specified elements and either append or remove
      the elements not mentioned.
    - Example: `delimiter=" " elements="i1;i0"` → "Hello World?" becomes "World?;Hello".

## 3. Cutting Operations

Operations that cut or trim parts of the string from the start or end.

- **`<cutfromstart>X</cutfromstart>`**
    - Removes the first `X` characters from the start of the string.
    - Example: `cutstart="6"` → "abcdef" becomes "f".

- **`<cutfromend>X</cutfromend>`**
    - Removes the last `X` characters from the end of the string.
    - Example: `cutend="1"` → "hello!" becomes "hello".

- **`<trimspaces/>`**
    - Trims whitespace from the beginning and end of the string.
    - Example: `"   hello   "` becomes `"hello"`.

## 4. Prepending and Appending

These operations add characters or text to the start or end of the string.

- **`<prepend>X</prepend>`**
    - Adds `X` at the beginning of the string.
    - Example: `prepend="Do "` → "something" becomes "Do something".

- **`<append>X</append>`**
    - Appends `X` at the end of the string.
    - Example: `suffix=" done"` → "something" becomes "something done".

## 5. Time and Date Operations

Operations that modify or format time and date representations.

- **`<reformatdate index="X" from="old_format">new_format</reformatdate>`**
    - Converts a date at `index X` using the old format `from` to the new format.
    - Example: `index="1", from="dd/MM/yyyy", new_format="yyyy_MMMM_dd"` → "12/02/2025" becomes "2025_February_12".

- **`<reformattime delimiter="X" index="Y" from="old_format">new_format</reformattime>`**
    - Reformats the time at `index Y` based on a split on X according to the new time format.
    - Example: `delimiter="_", index="0", from="HHmmss", new_format="HH:mm:ss"` → "162005_Hello?" becomes "16:20:
      05_Hello?".

- **`<millisdate index="x" index="Y">new_format</millisdate>`**
    - Converts the epoch millis to another date format
    - Example:

## 6. Regex-based Modifications

Operations that modify the string using regular expressions.

- **`<regexremove>pattern</rexremove>`**
    - Removes all characters from the string matching the specified pattern.
    - Example: `regexremove="[a-y]"` → "16:20:05_February_" becomes "16:20:05_F_".

- **`<remove>substring</remove>`**
    - Removes all occurrences of the specified substring.
    - Example: `remove="3"` → "321 Go!" becomes "21 Go!".

## 7. Inserting and Adding

Operations that insert elements at specific positions in the string.

- **`<insert delimiter="X" index="Y">Z</insert>`**
    - Inserts `Z` at the `index` position after split in `X` in the string.
    - Example: `insert index="7", Y=" World"` → "hello" becomes "hello World".

## 8. Index and Element Management

Operations that work with specific elements of the string based on indexes.

- **`<removeindex delimiter="X">Y</removeindex>`**
    - Removes the element at `Y` after splitting the string by delimiter `X`.
    - Example: `delimiter="_", removeindex="1"` → "16:20:05_February_" becomes "16:20:05_".
