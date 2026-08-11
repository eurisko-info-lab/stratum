# Lambda agent guidance

The Lambda language is pure untyped lambda calculus. Its grammar accepts only:

- variables such as `x` or `next_value`;
- abstractions written `\x. body`;
- left-associative application written `fn argument`;
- parentheses.

It has no `define`, `let`, numeric literal, conditional, arithmetic operator, or multi-argument abstraction syntax. Write `\a. \b. body`, not `lambda (a b)`.

Use `source check <path>` before `run <path>`. The declared `NormalizeToText` evaluator performs normal-order beta normalization and prints bound variables with generated names.

## Church encodings

```text
zero = \f. \x. x
one  = \f. \x. f x
add  = \m. \n. \f. \x. m f (n f x)
pair = \a. \b. \s. s a b
first = \p. p (\a. \b. a)
second = \p. p (\a. \b. b)
```

A Fibonacci iteration maps `(a, b)` to `(b, a + b)`, starts at `(0, 1)`, applies that step `n` times, and selects the first component. The reusable abstraction `\n. first (n step (pair zero one))` is installed as `languages/lambda/fibonacci.lambda`.

Do not transcribe or regenerate that definition. Preserve its exact validated bytes with:

```text
source copy languages/lambda/fibonacci.lambda fibonacci.lambda
```

Apply the definition to any Church numeral to compute that Fibonacci value:

```text
(<fibonacci definition>) (<Church numeral>)
```

For example, applying it to Church numeral 10 normalizes to Church numeral 55: two abstractions followed by exactly 55 applications of the first bound variable to the second. The Lambda language has no imports or named definitions, so an application source must contain the Fibonacci abstraction itself.
