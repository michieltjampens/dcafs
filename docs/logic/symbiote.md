### Symbiote Design Pattern in dcafs

In the context of dcafs, the **Symbiote** pattern is implemented to allow flexible management of **derived values**. The
primary goal of the Symbiote is to "mask" the complexity of handling derived values by encapsulating them in a way that
doesn’t change the way external modules interact with the system.

#### Key Concepts:

1. **Symbiote Structure**:
    - A **Symbiote** is a specialized class that extends an existing class (e.g., `BaseVal`, or a subclass like
      `RealVal`). It holds an internal array where the first element is the original value (the base `RealVal` or
      `BaseVal`), and subsequent elements represent derived values. These derived values can be transformations or
      calculated versions of the original value.
    - The Symbiote only contains instances of the same class (or subclasses) that it extends, maintaining internal
      consistency. Whether the derived values are of the same class or subclass doesn't change the overall functionality
      but ensures the Symbiote’s internal array holds logically related objects.

2. **Derived Values**:
    - **Derived values** are values calculated from an original `RealVal` or `BaseVal`, such as statistical operations (
      e.g., mean, variance, standard deviation). These derived values can be layered or further transformed.
    - When a derived value is created, it becomes part of the Symbiote’s internal array, linked to the original value.

3. **Symbiote Interaction with External Systems**:
    - To the external world (other modules or the user interface), the Symbiote appears as a single `RealVal`. This
      gives the illusion that the external system is interacting with the original value, even if it’s actually
      interacting with a derived value.
    - When a value is updated, the first element in the Symbiote’s array (the base or root value) is the one that
      triggers changes to all derived values. This first element represents the foundational value from which other
      values are derived.

4. **Updates and Flexibility**:
    - The Symbiote allows for derived values to be altered independently of the base value. The derived values are "
      self-contained" and can be modified without affecting the underlying system, meaning users can directly reset
      things like `max`, `min`, etc., on individual derived values without impacting other values in the system.
    - If the base value changes, the Symbiote will propagate those changes to the derived values, ensuring consistency
      across the entire chain of transformations.

5. **Handling of Derived Values**:
    - If a derived value itself needs to be derived further (e.g., performing additional calculations), it can be
      encapsulated in a new Symbiote. This allows for a chain of transformations, where each layer of derived values is
      linked to its predecessor.
    - However, each derived value can be altered directly if needed. The Symbiote doesn’t restrict modifications to any
      element in its array, ensuring flexibility for resetting values like `max`, `min`, or other calculations.

6. **Symbiote Limitations**:
    - A Symbiote is expected to hold only instances of the class it extends (i.e., `RealVal` or its subclasses like
      `BaseVal`). However, this design choice ensures that the system remains consistent while still allowing
      flexibility in how derived values are managed.

#### Benefits of the Symbiote Pattern:

- **Transparency**: External systems can interact with the Symbiote as though it is a regular `RealVal`, abstracting the
  complexity of derived values.
- **Flexibility**: The pattern allows for complex calculations and transformations without requiring changes to the
  original base values. Each derived value can be modified independently without affecting the others.
- **Scalability**: The system can easily grow, as each new derived value can simply be added to the Symbiote’s internal
  array. There’s no need to refactor or change the structure of existing values to introduce new transformations.
- **Control Over Derived Values**: Users can independently manipulate derived values (e.g., reset `max` or `min` values)
  without impacting other derived or base values in the system.
