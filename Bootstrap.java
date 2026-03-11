import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Bootstrap: compiles all SmallC source files and then runs the compiler
 * on a Small-C test program, all without needing javac on PATH.
 */
public class Bootstrap {

    public static void main(String[] args) throws Exception {

        String srcDir = args.length > 0 ? args[0] : ".";
        String testFile = args.length > 1 ? args[1] : "hello.c";

        // Compile all SmallC compiler sources
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No Java compiler available.");
            System.exit(1);
        }

        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);

        List<File> sources = Arrays.asList(
            new File(srcDir + "/Lexer.java"),
            new File(srcDir + "/AST.java"),
            new File(srcDir + "/Parser.java"),
            new File(srcDir + "/CodeGen.java"),
            new File(srcDir + "/SmallC.java")
        );

        File outDir = new File(srcDir + "/classes");
        outDir.mkdirs();

        List<String> options = Arrays.asList("-d", outDir.getAbsolutePath(), "-source", "11", "-target", "11");

        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();

        boolean ok = compiler.getTask(null, fm, diags, options, null, units).call();

        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            System.err.println(d.getKind() + ": " + d.getMessage(null));
            if (d.getSource() != null)
                System.err.println("  in " + d.getSource().getName() + " at line " + d.getLineNumber());
        }

        if (!ok) {
            System.err.println("Compilation of SmallC compiler FAILED.");
            System.exit(1);
        }

        System.out.println("SmallC compiler compiled successfully.");
        System.out.println();

        // Now run SmallC on the test file via class loader
        URLClassLoader cl = new URLClassLoader(
            new URL[]{ outDir.toURI().toURL() },
            Bootstrap.class.getClassLoader()
        );

        Class<?> smallcClass = cl.loadClass("SmallC");
        // Change working directory context by copying test file
        String[] smallcArgs = { new File(srcDir + "/" + testFile).getAbsolutePath() };

        // Redirect output to stdout
        java.lang.reflect.Method mainMethod = smallcClass.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) smallcArgs);
    }
}
