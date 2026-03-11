# SmallC — A Small-C to Java Bytecode Compiler

A compiler for a subset of C, inspired by Ron Cain's original **Small-C** published in *Dr. Dobb's Journal* (May/June 1980), that compiles directly to JVM `.class` files — with no external libraries and no intermediate steps.

Write Small-C source, run the compiler, execute the result with `java`. That's it.

```
$ java SmallC hello.c
Lexing...    12 tokens
Parsing...   1 function(s), 0 global variable(s)
Generating bytecode... 390 bytes
Written: Hello.class

$ java Hello
Hello, World!
```

---

## Background

In 1980, Ron Cain published a C compiler small enough to fit in a magazine — approximately 2,000 lines of C that could compile a useful subset of the language and, remarkably, compile itself. It was a landmark demonstration that compilers need not be arcane, monolithic systems accessible only to specialists.

This project is written in that same spirit. The entire compiler — lexer, parser, AST, and bytecode emitter — fits in five Java source files totalling around 1,000 lines. There are no dependencies beyond the Java standard library. The bytecode emitter writes raw JVM class files by hand, building the constant pool, method tables, and instruction stream from scratch.

It is intended as an educational tool for anyone who wants to understand how a real compiler works, from source characters to executable bytecode, without the noise of a production system.

---

## Features

### Language Support

| Feature | Supported |
|---|---|
| `int` and `char` scalar variables | ✅ |
| `int[]` and `char[]` arrays | ✅ |
| Global and local variables | ✅ |
| Functions with parameters | ✅ |
| `void`, `int`, `char` return types | ✅ |
| `if` / `else` | ✅ |
| `while` loop | ✅ |
| `do` / `while` loop | ✅ |
| `for` loop | ✅ |
| `break` and `continue` | ✅ |
| `return` | ✅ |
| Arithmetic: `+ - * / %` | ✅ |
| Bitwise: `& \| ^ ~ << >>` | ✅ |
| Logical: `&& \|\| !` | ✅ |
| Relational: `== != < <= > >=` | ✅ |
| Assignment: `= += -= *= /=` | ✅ |
| Pre/post increment and decrement: `++ --` | ✅ |
| Integer, character and string literals | ✅ |
| Hexadecimal literals (`0x1F`) | ✅ |
| Recursive functions | ✅ |
| Line (`//`) and block (`/* */`) comments | ✅ |
| Built-in: `putchar()`, `printf()`, `exit()` | ✅ |
| Pointers | ❌ |
| `struct` / `union` | ❌ |
| `float` / `double` | ❌ |
| Preprocessor (`#define`, `#include`) | ❌ |
| `switch` / `case` | ❌ |
| Multiple source files | ❌ |

### Compiler Features

- **No external dependencies** — pure Java standard library
- **Raw bytecode generation** — writes JVM `.class` files from scratch, no ASM or BCEL
- **Java 6 class file format** (version 50) for maximum compatibility
- **Two-pass branch resolution** — forward labels patched after code generation
- **Single-pass lexer** — character-at-a-time in the tradition of the original Small-C
- **Recursive descent parser** — one method per grammar rule, easy to follow and extend

---

## Project Structure

```
SmallC.java        Main driver — reads source, orchestrates the four phases
Lexer.java         Tokeniser — converts source characters into a token stream
AST.java           Abstract Syntax Tree — node hierarchy for the entire language
Parser.java        Recursive descent parser — builds the AST from the token stream
CodeGen.java       Bytecode emitter — walks the AST and writes JVM class files
Bootstrap.java     Build helper — compiles the compiler on JRE-only systems
```

### Compiler Pipeline

```
Source file (.c)
      │
      ▼
  ┌────────┐
  │  Lexer │  Characters → Tokens
  └────────┘
      │
      ▼
  ┌────────┐
  │ Parser │  Tokens → Abstract Syntax Tree
  └────────┘
      │
      ▼
  ┌─────────┐
  │ CodeGen │  AST → JVM bytecode
  └─────────┘
      │
      ▼
  .class file  (runs directly with `java`)
```

---

## Getting Started

### Requirements

- Java 11 or later (runtime)
- `javac` on your PATH **or** use `Bootstrap.java` (see below)

### Building the Compiler

If you have the JDK installed:

```bash
javac Lexer.java AST.java Parser.java CodeGen.java SmallC.java
```

If you only have the JRE (no `javac`), use the bootstrap runner which compiles via `javax.tools`:

```bash
java Bootstrap.java . hello.c
```

### Compiling a Small-C Program

```bash
java SmallC hello.c
```

This produces `Hello.class` in the current directory. The class name is derived from the filename (capitalised). You can override it:

```bash
java SmallC hello.c MyProgram
```

### Running the Output

```bash
java Hello
```

---

## Examples

### Hello, World

```c
/* hello.c */
void main() {
    printf("Hello, World!\n");
}
```

```
$ java SmallC hello.c && java Hello
Hello, World!
```

### Arithmetic and Functions

```c
/* arithmetic.c */
int add(int a, int b) {
    return a + b;
}

void main() {
    int result;
    result = add(10, 32);
    printf(result);
    putchar('\n');
}
```

### Recursion

```c
/* factorial.c */
int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

void main() {
    printf(factorial(10));
    putchar('\n');
}
```

### Loops and Control Flow

```c
/* loops.c */
void main() {
    int i;
    int sum;
    sum = 0;

    for (i = 1; i <= 10; i++) {
        sum = sum + i;
    }

    printf(sum);    /* prints 55 */
    putchar('\n');

    i = 0;
    while (i < 26) {
        putchar('A' + i);
        i++;
    }
    putchar('\n');  /* prints ABCDEFGHIJKLMNOPQRSTUVWXYZ */
}
```

### Global Variables and Arrays

```c
/* arrays.c */
int data[5];
int total;

void main() {
    int i;
    total = 0;

    for (i = 0; i < 5; i++) {
        data[i] = i * i;
        total = total + data[i];
    }

    printf(total);   /* prints 30 (0+1+4+9+16) */
    putchar('\n');
}
```

---

## How It Works

### Lexer

The lexer is a single-pass, character-at-a-time scanner that converts source text into a flat list of typed `Token` objects. It handles all C operators (including multi-character ones like `<=`, `++`, `&&`), string and character escape sequences, hexadecimal integer literals, and both line and block comments. The design is directly descended from the lookahead-character approach used in Crenshaw's *Let's Build a Compiler* and Cain's original Small-C.

### Parser

The parser is a hand-written recursive descent parser with one method per grammar rule. Operator precedence is encoded directly in the call hierarchy — the deepest rule handles the tightest-binding operators. Assignment is right-associative. Short-circuit evaluation for `&&` and `||` is implemented correctly. The grammar is close to standard C within the supported subset.

### AST

The Abstract Syntax Tree is a hierarchy of plain Java classes implementing marker interfaces (`Stmt`, `Expr`, `Declaration`). It is intentionally simple — no visitor pattern, no type annotations at this stage — keeping the code readable and the structure obvious.

### Code Generator

The most technically interesting component. `CodeGen` writes a valid JVM `.class` file entirely from scratch by:

1. **Building a constant pool** — the JVM's string/symbol table, containing class names, method descriptors, field references and string literals
2. **Emitting bytecode** — the JVM is a stack machine, so C expressions map naturally to push/operate/store sequences
3. **Patching branches** — forward jumps are emitted with placeholder offsets and resolved in a second pass once all label positions are known
4. **Assembling the class file** — magic number, version, constant pool, access flags, fields, methods and code attributes written in the binary format the JVM expects

Global variables become `static int` fields. Local variables occupy JVM local variable slots. All `int` and `char` types are represented as JVM `int`. Arrays are `int[]` references.

The class file targets format version 50 (Java 6), which predates the stack map frame requirement introduced in Java 7 — this keeps the emitter simple while remaining runnable on all modern JVMs.

---

## Built-in Functions

| Function | Behaviour |
|---|---|
| `putchar(c)` | Writes a single character to stdout |
| `printf(x)` | Prints a string literal or integer to stdout |
| `exit(n)` | Calls `System.exit(n)` |

`printf` here is simplified — it accepts either a string literal or an integer expression, and prints it directly. It does not support format strings with `%d`, `%s` etc. For formatted output use `putchar` for characters and `printf` for integers or string constants.

---

## Limitations

This is an educational compiler implementing a useful but deliberately restricted subset of C. Notable omissions:

- **No pointers** — the most significant restriction; pointer arithmetic and `*`/`&` operators are not implemented
- **No preprocessor** — `#include` and `#define` are not supported
- **No structs** — only scalar and array types
- **No floating point** — integers only
- **No switch/case** — use if/else chains
- **Single file only** — no multi-file compilation or linking
- **printf is simplified** — no format string support

---

## Extending the Compiler

The codebase is designed to be readable and extended. Some suggested directions:

- **Add pointer support** — the most authentic Small-C extension; add `*` and `&` unary operators and pointer arithmetic to the parser and codegen
- **Add `switch`/`case`** — maps cleanly to JVM `tableswitch` or `lookupswitch` instructions
- **Add a type system** — currently all values are `int`; a symbol table with type tracking would enable `char` narrowing and better error messages
- **Add stack map frames** — would allow targeting Java 7+ class file format without `-noverify`
- **Add an optimiser** — constant folding, dead code elimination, even simple peephole optimisation over the bytecode stream

---

## Acknowledgements

- **Ron Cain** — for the original Small-C compiler, *Dr. Dobb's Journal*, May/June 1980
- **James Hendrix** — for the extended Small-C and the book *A Small C Compiler* (M&T Books)
- **Jack Crenshaw** — for *Let's Build a Compiler*, the clearest introduction to recursive descent parsing ever written
- **Tim Lindholm & Frank Yellin** — for *The Java Virtual Machine Specification*, the definitive reference for class file format and bytecode

---

## Author

Mario Gianota — [gianotamario@protonmail.com](mailto:gianotamario@protonmail.com)

---

## Licence

This project is released for educational and personal use. No warranty is expressed or implied. You are free to study, modify and redistribute it with attribution.
