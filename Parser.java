import java.util.*;

/**
 * Recursive descent parser for Small-C.
 *
 * Grammar (simplified BNF):
 *
 * program       ::= declaration*
 * declaration   ::= varDecl | functionDecl
 * functionDecl  ::= typeSpec Lexer.TT.IDENT '(' paramList ')' block
 * varDecl       ::= typeSpec Lexer.TT.IDENT ('[' Lexer.TT.INT_LIT ']')? ('=' expr)? ';'
 * paramList     ::= (param (',' param)*)?
 * param         ::= typeSpec Lexer.TT.IDENT
 * typeSpec      ::= ('int' | 'char' | 'void')
 * block         ::= '{' stmt* '}'
 * stmt          ::= block | ifStmt | whileStmt | doStmt | forStmt
 *                 | returnStmt | breakStmt | continueStmt
 *                 | varDecl | exprStmt | ';'
 * expr          ::= assignment
 * assignment    ::= logicalOr (assignOp assignment)?
 * logicalOr     ::= logicalAnd ('||' logicalAnd)*
 * logicalAnd    ::= bitwiseOr  ('&&' bitwiseOr)*
 * bitwiseOr     ::= bitwiseXor ('|' bitwiseXor)*
 * bitwiseXor    ::= bitwiseAnd ('^' bitwiseAnd)*
 * bitwiseAnd    ::= equality   ('&' equality)*
 * equality      ::= relational (('=='|'!=') relational)*
 * relational    ::= shift      (('<'|'<='|'>'|'>=') shift)*
 * shift         ::= additive   (('<<'|'>>') additive)*
 * additive      ::= multiplicative (('+'|'-') multiplicative)*
 * multiplicative::= unary (('*'|'/'|'%') unary)*
 * unary         ::= ('!'|'-'|'~'|'++'|'--') unary | postfix
 * postfix       ::= primary ('[' expr ']')? ('[' expr ']')? ('('args')')? ('++'|'--')?
 * primary       ::= Lexer.TT.INT_LIT | Lexer.TT.CHAR_LIT | Lexer.TT.STR_LIT | Lexer.TT.IDENT | '(' expr ')'
 */
public class Parser {

    private final List<Lexer.Token> tokens;
    private int pos;

    public Parser(List<Lexer.Token> tokens) {
        this.tokens = tokens;
        this.pos    = 0;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Lexer.Token peek()     { return tokens.get(pos); }
    private Lexer.Token advance()  { Lexer.Token t = tokens.get(pos); pos++; return t; }
    private boolean atEnd()        { return peek().type == Lexer.TT.EOF; }

    private boolean check(Lexer.TT type) { return peek().type == type; }

    private boolean match(Lexer.TT... types) {
        for (Lexer.TT t : types) {
            if (check(t)) { advance(); return true; }
        }
        return false;
    }

    private Lexer.Token expect(Lexer.TT type, String msg) {
        if (!check(type)) {
            throw new RuntimeException("Parse error at line " + peek().line +
                ": expected " + msg + " but got " + peek());
        }
        return advance();
    }

    private boolean isTypeSpec() {
        Lexer.TT t = peek().type;
        return t == Lexer.TT.KW_INT || t == Lexer.TT.KW_CHAR || t == Lexer.TT.KW_VOID;
    }

    private String parseTypeSpec() {
        Lexer.Token t = advance();
        return t.value;
    }

    // -----------------------------------------------------------------------
    // Program
    // -----------------------------------------------------------------------

    public AST.Program parseProgram() {
        List<AST.Declaration> decls = new ArrayList<>();
        while (!atEnd()) {
            decls.add(parseDeclaration());
        }
        return new AST.Program(decls);
    }

    // -----------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------

    private AST.Declaration parseDeclaration() {
        String type = parseTypeSpec();
        String name = expect(Lexer.TT.IDENT, "identifier").value;

        // Lookahead: if next is '(' it's a function
        if (check(Lexer.TT.LPAREN)) {
            return parseFunctionDecl(type, name);
        } else {
            return parseGlobalVar(type, name);
        }
    }

    private AST.FunctionDecl parseFunctionDecl(String returnType, String name) {
        expect(Lexer.TT.LPAREN, "'('");
        List<AST.Param> params = parseParamList();
        expect(Lexer.TT.RPAREN, "')'");
        AST.Block body = parseBlock();
        return new AST.FunctionDecl(returnType, name, params, body);
    }

    private List<AST.Param> parseParamList() {
        List<AST.Param> params = new ArrayList<>();
        if (check(Lexer.TT.RPAREN)) return params; // empty
        if (check(Lexer.TT.KW_VOID) && tokens.get(pos+1).type == Lexer.TT.RPAREN) {
            advance(); return params; // void means no params
        }
        do {
            String ptype = parseTypeSpec();
            String pname = expect(Lexer.TT.IDENT, "parameter name").value;
            params.add(new AST.Param(ptype, pname));
        } while (match(Lexer.TT.COMMA));
        return params;
    }

    private AST.GlobalVar parseGlobalVar(String type, String name) {
        int arraySize = 0;
        AST.Expr init = null;

        if (match(Lexer.TT.LBRACKET)) {
            Lexer.Token sizeTok = expect(Lexer.TT.INT_LIT, "array size");
            arraySize = Integer.parseInt(sizeTok.value);
            expect(Lexer.TT.RBRACKET, "']'");
        }

        if (match(Lexer.TT.ASSIGN)) {
            init = parseExpr();
        }

        expect(Lexer.TT.SEMICOLON, "';'");
        return new AST.GlobalVar(type, name, arraySize, init);
    }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    private AST.Block parseBlock() {
        expect(Lexer.TT.LBRACE, "'{'");
        List<AST.Stmt> stmts = new ArrayList<>();
        while (!check(Lexer.TT.RBRACE) && !atEnd()) {
            stmts.add(parseStmt());
        }
        expect(Lexer.TT.RBRACE, "'}'");
        return new AST.Block(stmts);
    }

    private AST.Stmt parseStmt() {
        Lexer.TT t = peek().type;

        if (t == Lexer.TT.LBRACE)     return parseBlock();
        if (t == Lexer.TT.KW_IF)      return parseIf();
        if (t == Lexer.TT.KW_WHILE)   return parseWhile();
        if (t == Lexer.TT.KW_DO)      return parseDo();
        if (t == Lexer.TT.KW_FOR)     return parseFor();
        if (t == Lexer.TT.KW_RETURN)  return parseReturn();
        if (t == Lexer.TT.KW_BREAK)   { advance(); expect(Lexer.TT.SEMICOLON,"';'"); return new AST.BreakStmt(); }
        if (t == Lexer.TT.KW_CONTINUE){ advance(); expect(Lexer.TT.SEMICOLON,"';'"); return new AST.ContinueStmt(); }
        if (t == Lexer.TT.SEMICOLON)  { advance(); return new AST.EmptyStmt(); }
        if (isTypeSpec())    return parseLocalVar();

        // Expression statement
        AST.Expr e = parseExpr();
        expect(Lexer.TT.SEMICOLON, "';'");
        return new AST.ExprStmt(e);
    }

    private AST.Stmt parseLocalVar() {
        String type      = parseTypeSpec();
        String name      = expect(Lexer.TT.IDENT, "variable name").value;
        int    arraySize = 0;
        AST.Expr init    = null;

        if (match(Lexer.TT.LBRACKET)) {
            arraySize = Integer.parseInt(expect(Lexer.TT.INT_LIT, "array size").value);
            expect(Lexer.TT.RBRACKET, "']'");
        }
        if (match(Lexer.TT.ASSIGN)) {
            init = parseExpr();
        }
        expect(Lexer.TT.SEMICOLON, "';'");
        return new AST.VarDecl(type, name, arraySize, init);
    }

    private AST.Stmt parseIf() {
        expect(Lexer.TT.KW_IF, "'if'");
        expect(Lexer.TT.LPAREN, "'('");
        AST.Expr cond = parseExpr();
        expect(Lexer.TT.RPAREN, "')'");
        AST.Stmt then = parseStmt();
        AST.Stmt else_ = null;
        if (match(Lexer.TT.KW_ELSE)) {
            else_ = parseStmt();
        }
        return new AST.IfStmt(cond, then, else_);
    }

    private AST.Stmt parseWhile() {
        expect(Lexer.TT.KW_WHILE, "'while'");
        expect(Lexer.TT.LPAREN, "'('");
        AST.Expr cond = parseExpr();
        expect(Lexer.TT.RPAREN, "')'");
        AST.Stmt body = parseStmt();
        return new AST.WhileStmt(cond, body);
    }

    private AST.Stmt parseDo() {
        expect(Lexer.TT.KW_DO, "'do'");
        AST.Stmt body = parseStmt();
        expect(Lexer.TT.KW_WHILE, "'while'");
        expect(Lexer.TT.LPAREN, "'('");
        AST.Expr cond = parseExpr();
        expect(Lexer.TT.RPAREN, "')'");
        expect(Lexer.TT.SEMICOLON, "';'");
        return new AST.DoWhileStmt(body, cond);
    }

    private AST.Stmt parseFor() {
        expect(Lexer.TT.KW_FOR, "'for'");
        expect(Lexer.TT.LPAREN, "'('");

        AST.Stmt init = null;
        if (!check(Lexer.TT.SEMICOLON)) {
            if (isTypeSpec()) {
                init = parseLocalVar();
            } else {
                init = new AST.ExprStmt(parseExpr());
                expect(Lexer.TT.SEMICOLON, "';'");
            }
        } else {
            advance(); // consume ';'
        }

        AST.Expr cond = null;
        if (!check(Lexer.TT.SEMICOLON)) cond = parseExpr();
        expect(Lexer.TT.SEMICOLON, "';'");

        AST.Expr update = null;
        if (!check(Lexer.TT.RPAREN)) update = parseExpr();
        expect(Lexer.TT.RPAREN, "')'");

        AST.Stmt body = parseStmt();
        return new AST.ForStmt(init, cond, update, body);
    }

    private AST.Stmt parseReturn() {
        expect(Lexer.TT.KW_RETURN, "'return'");
        AST.Expr val = null;
        if (!check(Lexer.TT.SEMICOLON)) val = parseExpr();
        expect(Lexer.TT.SEMICOLON, "';'");
        return new AST.ReturnStmt(val);
    }

    // -----------------------------------------------------------------------
    // Expressions - recursive descent with precedence climbing
    // -----------------------------------------------------------------------

    private AST.Expr parseExpr() {
        return parseAssignment();
    }

    private AST.Expr parseAssignment() {
        AST.Expr left = parseLogicalOr();

        Lexer.TT t = peek().type;
        if (t == Lexer.TT.ASSIGN || t == Lexer.TT.PLUSEQ || t == Lexer.TT.MINUSEQ || t == Lexer.TT.STAREQ || t == Lexer.TT.SLASHEQ) {
            String op = advance().value;
            AST.Expr right = parseAssignment(); // right-associative
            return new AST.Assign(left, op, right);
        }
        return left;
    }

    private AST.Expr parseLogicalOr() {
        AST.Expr left = parseLogicalAnd();
        while (check(Lexer.TT.OR)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseLogicalAnd());
        }
        return left;
    }

    private AST.Expr parseLogicalAnd() {
        AST.Expr left = parseBitwiseOr();
        while (check(Lexer.TT.AND)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseBitwiseOr());
        }
        return left;
    }

    private AST.Expr parseBitwiseOr() {
        AST.Expr left = parseBitwiseXor();
        while (check(Lexer.TT.PIPE)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseBitwiseXor());
        }
        return left;
    }

    private AST.Expr parseBitwiseXor() {
        AST.Expr left = parseBitwiseAnd();
        while (check(Lexer.TT.CARET)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseBitwiseAnd());
        }
        return left;
    }

    private AST.Expr parseBitwiseAnd() {
        AST.Expr left = parseEquality();
        while (check(Lexer.TT.AMP)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseEquality());
        }
        return left;
    }

    private AST.Expr parseEquality() {
        AST.Expr left = parseRelational();
        while (check(Lexer.TT.EQ) || check(Lexer.TT.NEQ)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseRelational());
        }
        return left;
    }

    private AST.Expr parseRelational() {
        AST.Expr left = parseShift();
        while (check(Lexer.TT.LT) || check(Lexer.TT.LE) || check(Lexer.TT.GT) || check(Lexer.TT.GE)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseShift());
        }
        return left;
    }

    private AST.Expr parseShift() {
        AST.Expr left = parseAdditive();
        while (check(Lexer.TT.LSHIFT) || check(Lexer.TT.RSHIFT)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseAdditive());
        }
        return left;
    }

    private AST.Expr parseAdditive() {
        AST.Expr left = parseMultiplicative();
        while (check(Lexer.TT.PLUS) || check(Lexer.TT.MINUS)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseMultiplicative());
        }
        return left;
    }

    private AST.Expr parseMultiplicative() {
        AST.Expr left = parseUnary();
        while (check(Lexer.TT.STAR) || check(Lexer.TT.SLASH) || check(Lexer.TT.PERCENT)) {
            String op = advance().value;
            left = new AST.BinOp(op, left, parseUnary());
        }
        return left;
    }

    private AST.Expr parseUnary() {
        if (check(Lexer.TT.BANG)) {
            advance(); return new AST.UnaryOp("!", parseUnary());
        }
        if (check(Lexer.TT.MINUS)) {
            advance(); return new AST.UnaryOp("-", parseUnary());
        }
        if (check(Lexer.TT.TILDE)) {
            advance(); return new AST.UnaryOp("~", parseUnary());
        }
        if (check(Lexer.TT.INC)) {
            advance(); return new AST.PreIncDec("++", parseUnary());
        }
        if (check(Lexer.TT.DEC)) {
            advance(); return new AST.PreIncDec("--", parseUnary());
        }
        return parsePostfix();
    }

    private AST.Expr parsePostfix() {
        AST.Expr expr = parsePrimary();

        // Array index
        if (check(Lexer.TT.LBRACKET)) {
            advance();
            AST.Expr index = parseExpr();
            expect(Lexer.TT.RBRACKET, "']'");
            String name = (expr instanceof AST.VarRef) ? ((AST.VarRef)expr).name : "?";
            expr = new AST.ArrayRef(name, index);
        }

        // Post-increment / post-decrement
        if (check(Lexer.TT.INC)) { advance(); return new AST.PostIncDec("++", expr); }
        if (check(Lexer.TT.DEC)) { advance(); return new AST.PostIncDec("--", expr); }

        return expr;
    }

    private AST.Expr parsePrimary() {
        Lexer.Token t = peek();

        if (t.type == Lexer.TT.INT_LIT) {
            advance();
            int val = t.value.startsWith("0x") || t.value.startsWith("0X")
                    ? Integer.parseInt(t.value.substring(2), 16)
                    : Integer.parseInt(t.value);
            return new AST.IntLit(val);
        }

        if (t.type == Lexer.TT.CHAR_LIT) {
            advance();
            return new AST.CharLit(Integer.parseInt(t.value));
        }

        if (t.type == Lexer.TT.STR_LIT) {
            advance();
            return new AST.StringLit(t.value);
        }

        if (t.type == Lexer.TT.IDENT) {
            advance();
            String name = t.value;

            // Function call
            if (check(Lexer.TT.LPAREN)) {
                advance();
                List<AST.Expr> args = new ArrayList<>();
                if (!check(Lexer.TT.RPAREN)) {
                    do {
                        args.add(parseExpr());
                    } while (match(Lexer.TT.COMMA));
                }
                expect(Lexer.TT.RPAREN, "')'");
                return new AST.CallExpr(name, args);
            }

            return new AST.VarRef(name);
        }

        if (t.type == Lexer.TT.LPAREN) {
            advance();
            AST.Expr e = parseExpr();
            expect(Lexer.TT.RPAREN, "')'");
            return e;
        }

        throw new RuntimeException("Parse error at line " + t.line +
            ": unexpected token " + t);
    }
}
