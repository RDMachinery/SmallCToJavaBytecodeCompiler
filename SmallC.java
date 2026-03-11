import java.io.*;
import java.nio.file.*;
import java.util.List;

/**
 * SmallC - A Small-C to Java bytecode compiler.
 *
 * Based on Ron Cain's original Small-C (Dr. Dobb's Journal, 1980).
 *
 * Usage:
 *   java SmallC <source.c> [output-class-name]
 *
 * The compiler performs four passes:
 *   1. Lexing   - tokenise the source into a token stream
 *   2. Parsing  - parse tokens into an Abstract Syntax Tree
 *   3. CodeGen  - walk the AST and emit JVM bytecode
 *   4. Output   - write the .class file to disk
 *
 * The resulting .class file can be run directly with:
 *   java <ClassName>
 *
 * Supported Small-C subset:
 *   - int and char scalar variables (global and local)
 *   - int[] and char[] arrays
 *   - Functions with int/char parameters returning int/char/void
 *   - if/else, while, do/while, for
 *   - break, continue, return
 *   - Full arithmetic: + - * / %
 *   - Bitwise: & | ^ ~ << >>
 *   - Logical: && || !
 *   - Relational: == != < <= > >=
 *   - Assignment: = += -= *= /=
 *   - Pre/post increment and decrement: ++ --
 *   - Built-in functions: putchar(), printf(), exit()
 *   - String literals, integer literals, character literals
 *   - Line and block comments
 */
public class SmallC {

    public static void main(String[] args) throws IOException {

        if (args.length < 1) {
            System.err.println("Usage: java SmallC <source.c> [ClassName]");
            System.exit(1);
        }

        String sourceFile = args[0];
        String className  = args.length > 1 ? args[1] : deriveClassName(sourceFile);

        System.out.println("SmallC Compiler -- Java Bytecode Backend");
        System.out.println("Based on Ron Cain's Small-C (Dr. Dobb's Journal, 1980)");
        System.out.println();
        System.out.println("Source:  " + sourceFile);
        System.out.println("Class:   " + className);
        System.out.println();

        // Read source
        String source = new String(Files.readAllBytes(Paths.get(sourceFile)));

        // Phase 1: Lex
        System.out.print("Lexing... ");
        Lexer lexer = new Lexer(source);
        List<Lexer.Token> tokens = lexer.tokenize();
        System.out.println(tokens.size() + " tokens");

        // Phase 2: Parse
        System.out.print("Parsing... ");
        Parser parser = new Parser(tokens);
        AST.Program program = parser.parseProgram();
        int funcCount = (int) program.decls.stream()
            .filter(d -> d instanceof AST.FunctionDecl).count();
        int varCount  = (int) program.decls.stream()
            .filter(d -> d instanceof AST.GlobalVar).count();
        System.out.println(funcCount + " function(s), " + varCount + " global variable(s)");

        // Phase 3: Code generation
        System.out.print("Generating bytecode... ");
        CodeGen gen = new CodeGen(className, program);
        byte[] classFile = gen.generate();
        System.out.println(classFile.length + " bytes");

        // Phase 4: Write .class file
        String outFile = className + ".class";
        Files.write(Paths.get(outFile), classFile);
        System.out.println("Written: " + outFile);
        System.out.println();
        System.out.println("Run with: java " + className);
    }

    private static String deriveClassName(String filename) {
        String base = Paths.get(filename).getFileName().toString();
        if (base.contains(".")) base = base.substring(0, base.lastIndexOf('.'));
        // Capitalise first letter to match Java convention
        if (base.isEmpty()) return "Program";
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }
}
