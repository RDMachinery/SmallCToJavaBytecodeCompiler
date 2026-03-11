import java.util.List;

/**
 * Abstract Syntax Tree node hierarchy for Small-C.
 *
 * Small-C supports:
 *   - int and char scalar variables (global and local)
 *   - int[] and char[] arrays (global only in original; local here too)
 *   - Functions with parameters
 *   - if/else, while, do/while, for
 *   - return, break, continue
 *   - Full arithmetic, relational, logical, bitwise expressions
 *   - Assignment and compound assignment
 *   - Pre/post increment and decrement
 *   - putchar() and printf() as built-ins
 */
public class AST {

    // -----------------------------------------------------------------------
    // Top level
    // -----------------------------------------------------------------------

    public static class Program {
        public final List<Declaration> decls;
        public Program(List<Declaration> decls) { this.decls = decls; }
    }

    public interface Declaration {}

    public static class FunctionDecl implements Declaration {
        public final String      returnType; // "int", "char", "void"
        public final String      name;
        public final List<Param> params;
        public final Block       body;
        public FunctionDecl(String returnType, String name,
                            List<Param> params, Block body) {
            this.returnType = returnType;
            this.name       = name;
            this.params     = params;
            this.body       = body;
        }
    }

    public static class GlobalVar implements Declaration {
        public final String type;   // "int" or "char"
        public final String name;
        public final int    arraySize; // 0 = scalar
        public final Expr   init;      // may be null
        public GlobalVar(String type, String name, int arraySize, Expr init) {
            this.type      = type;
            this.name      = name;
            this.arraySize = arraySize;
            this.init      = init;
        }
    }

    public static class Param {
        public final String type;
        public final String name;
        public Param(String type, String name) {
            this.type = type; this.name = name;
        }
    }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    public interface Stmt {}

    public static class Block implements Stmt {
        public final List<Stmt> stmts;
        public Block(List<Stmt> stmts) { this.stmts = stmts; }
    }

    public static class VarDecl implements Stmt {
        public final String type;
        public final String name;
        public final int    arraySize; // 0 = scalar
        public final Expr   init;      // may be null
        public VarDecl(String type, String name, int arraySize, Expr init) {
            this.type      = type;
            this.name      = name;
            this.arraySize = arraySize;
            this.init      = init;
        }
    }

    public static class ExprStmt implements Stmt {
        public final Expr expr;
        public ExprStmt(Expr expr) { this.expr = expr; }
    }

    public static class IfStmt implements Stmt {
        public final Expr cond;
        public final Stmt then;
        public final Stmt else_; // may be null
        public IfStmt(Expr cond, Stmt then, Stmt else_) {
            this.cond  = cond;
            this.then  = then;
            this.else_ = else_;
        }
    }

    public static class WhileStmt implements Stmt {
        public final Expr cond;
        public final Stmt body;
        public WhileStmt(Expr cond, Stmt body) {
            this.cond = cond; this.body = body;
        }
    }

    public static class DoWhileStmt implements Stmt {
        public final Stmt body;
        public final Expr cond;
        public DoWhileStmt(Stmt body, Expr cond) {
            this.body = body; this.cond = cond;
        }
    }

    public static class ForStmt implements Stmt {
        public final Stmt init;   // may be null
        public final Expr cond;   // may be null
        public final Expr update; // may be null
        public final Stmt body;
        public ForStmt(Stmt init, Expr cond, Expr update, Stmt body) {
            this.init   = init;
            this.cond   = cond;
            this.update = update;
            this.body   = body;
        }
    }

    public static class ReturnStmt implements Stmt {
        public final Expr value; // may be null
        public ReturnStmt(Expr value) { this.value = value; }
    }

    public static class BreakStmt    implements Stmt {}
    public static class ContinueStmt implements Stmt {}
    public static class EmptyStmt    implements Stmt {}

    // -----------------------------------------------------------------------
    // Expressions
    // -----------------------------------------------------------------------

    public interface Expr {}

    public static class IntLit implements Expr {
        public final int value;
        public IntLit(int value) { this.value = value; }
    }

    public static class CharLit implements Expr {
        public final int value;
        public CharLit(int value) { this.value = value; }
    }

    public static class StringLit implements Expr {
        public final String value;
        public StringLit(String value) { this.value = value; }
    }

    public static class VarRef implements Expr {
        public final String name;
        public VarRef(String name) { this.name = name; }
    }

    public static class ArrayRef implements Expr {
        public final String name;
        public final Expr   index;
        public ArrayRef(String name, Expr index) {
            this.name = name; this.index = index;
        }
    }

    public static class Assign implements Expr {
        public final Expr   target;
        public final Expr   value;
        public final String op;   // "=", "+=", "-=", "*=", "/="
        public Assign(Expr target, String op, Expr value) {
            this.target = target;
            this.op     = op;
            this.value  = value;
        }
    }

    public static class BinOp implements Expr {
        public final String op;
        public final Expr   left, right;
        public BinOp(String op, Expr left, Expr right) {
            this.op = op; this.left = left; this.right = right;
        }
    }

    public static class UnaryOp implements Expr {
        public final String op;  // "-", "!", "~", "&", "*"
        public final Expr   operand;
        public UnaryOp(String op, Expr operand) {
            this.op = op; this.operand = operand;
        }
    }

    public static class PreIncDec implements Expr {
        public final String op;  // "++" or "--"
        public final Expr   target;
        public PreIncDec(String op, Expr target) {
            this.op = op; this.target = target;
        }
    }

    public static class PostIncDec implements Expr {
        public final String op;  // "++" or "--"
        public final Expr   target;
        public PostIncDec(String op, Expr target) {
            this.op = op; this.target = target;
        }
    }

    public static class CallExpr implements Expr {
        public final String     name;
        public final List<Expr> args;
        public CallExpr(String name, List<Expr> args) {
            this.name = name; this.args = args;
        }
    }
}
