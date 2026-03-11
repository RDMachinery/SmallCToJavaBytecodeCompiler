import java.util.*;

/**
 * Lexer for Small-C.
 *
 * Recognises the token set of Ron Cain's Small-C subset:
 *   - Integer literals
 *   - String literals
 *   - Character literals
 *   - Identifiers and keywords
 *   - All C operators and punctuation
 *
 * Single-pass, character-at-a-time design in the spirit of the original.
 */
public class Lexer {

    // Token types
    public enum TT {
        // Literals
        INT_LIT, CHAR_LIT, STR_LIT,
        // Identifier / keyword
        IDENT,
        // Keywords
        KW_INT, KW_CHAR, KW_IF, KW_ELSE, KW_WHILE, KW_FOR,
        KW_RETURN, KW_BREAK, KW_CONTINUE,
        KW_VOID, KW_DO,
        // Operators
        PLUS, MINUS, STAR, SLASH, PERCENT,
        AMP, PIPE, CARET, TILDE, BANG,
        LSHIFT, RSHIFT,
        EQ, NEQ, LT, LE, GT, GE,
        AND, OR,
        ASSIGN,
        PLUSEQ, MINUSEQ, STAREQ, SLASHEQ,
        INC, DEC,
        // Punctuation
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
        SEMICOLON, COMMA, COLON,
        // Special
        EOF
    }

    public static class Token {
        public final TT type;
        public final String value;
        public final int line;

        public Token(TT type, String value, int line) {
            this.type  = type;
            this.value = value;
            this.line  = line;
        }

        @Override
        public String toString() {
            return type + "(" + value + ")@" + line;
        }
    }

    private static final Map<String, TT> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("int",      TT.KW_INT);
        KEYWORDS.put("char",     TT.KW_CHAR);
        KEYWORDS.put("if",       TT.KW_IF);
        KEYWORDS.put("else",     TT.KW_ELSE);
        KEYWORDS.put("while",    TT.KW_WHILE);
        KEYWORDS.put("for",      TT.KW_FOR);
        KEYWORDS.put("return",   TT.KW_RETURN);
        KEYWORDS.put("break",    TT.KW_BREAK);
        KEYWORDS.put("continue", TT.KW_CONTINUE);
        KEYWORDS.put("void",     TT.KW_VOID);
        KEYWORDS.put("do",       TT.KW_DO);
    }

    private final String src;
    private int pos;
    private int line;
    private final List<Token> tokens = new ArrayList<>();

    public Lexer(String src) {
        this.src  = src;
        this.pos  = 0;
        this.line = 1;
    }

    private char peek()              { return pos < src.length() ? src.charAt(pos) : '\0'; }
    private char peek2()             { return pos+1 < src.length() ? src.charAt(pos+1) : '\0'; }
    private char advance()           { char c = peek(); pos++; if(c=='\n') line++; return c; }
    private boolean atEnd()          { return pos >= src.length(); }
    private boolean match(char c)    { if(peek()==c){ advance(); return true; } return false; }

    public List<Token> tokenize() {
        while (!atEnd()) {
            skipWhitespaceAndComments();
            if (atEnd()) break;
            readToken();
        }
        tokens.add(new Token(TT.EOF, "", line));
        return tokens;
    }

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peek2() == '/') {
                // Line comment
                while (!atEnd() && peek() != '\n') advance();
            } else if (c == '/' && peek2() == '*') {
                // Block comment
                advance(); advance();
                while (!atEnd()) {
                    if (peek() == '*' && peek2() == '/') { advance(); advance(); break; }
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private void readToken() {
        int startLine = line;
        char c = advance();

        if (Character.isLetter(c) || c == '_') {
            readIdent(c, startLine);
        } else if (Character.isDigit(c)) {
            readInt(c, startLine);
        } else if (c == '\'') {
            readChar(startLine);
        } else if (c == '"') {
            readString(startLine);
        } else {
            readOperator(c, startLine);
        }
    }

    private void readIdent(char first, int startLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(first);
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(advance());
        }
        String word = sb.toString();
        TT kw = KEYWORDS.get(word);
        tokens.add(new Token(kw != null ? kw : TT.IDENT, word, startLine));
    }

    private void readInt(char first, int startLine) {
        StringBuilder sb = new StringBuilder();
        sb.append(first);
        // Hex
        if (first == '0' && (peek() == 'x' || peek() == 'X')) {
            sb.append(advance());
            while (!atEnd() && isHexDigit(peek())) sb.append(advance());
        } else {
            while (!atEnd() && Character.isDigit(peek())) sb.append(advance());
        }
        tokens.add(new Token(TT.INT_LIT, sb.toString(), startLine));
    }

    private void readChar(int startLine) {
        int val = 0;
        if (peek() == '\\') {
            advance();
            val = escapeChar(advance());
        } else {
            val = advance();
        }
        if (peek() == '\'') advance(); // closing quote
        tokens.add(new Token(TT.CHAR_LIT, String.valueOf(val), startLine));
    }

    private void readString(int startLine) {
        StringBuilder sb = new StringBuilder();
        while (!atEnd() && peek() != '"') {
            if (peek() == '\\') {
                advance();
                sb.append((char) escapeChar(advance()));
            } else {
                sb.append(advance());
            }
        }
        if (!atEnd()) advance(); // closing "
        tokens.add(new Token(TT.STR_LIT, sb.toString(), startLine));
    }

    private int escapeChar(char c) {
        switch (c) {
            case 'n':  return '\n';
            case 't':  return '\t';
            case 'r':  return '\r';
            case '0':  return '\0';
            case '\\': return '\\';
            case '\'': return '\'';
            case '"':  return '"';
            default:   return c;
        }
    }

    private void readOperator(char c, int startLine) {
        switch (c) {
            case '+':
                if      (match('+')) add(TT.INC,    "++", startLine);
                else if (match('=')) add(TT.PLUSEQ,  "+=", startLine);
                else                 add(TT.PLUS,    "+",  startLine);
                break;
            case '-':
                if      (match('-')) add(TT.DEC,    "--", startLine);
                else if (match('=')) add(TT.MINUSEQ, "-=", startLine);
                else                 add(TT.MINUS,  "-",  startLine);
                break;
            case '*':
                if (match('=')) add(TT.STAREQ,  "*=", startLine);
                else            add(TT.STAR,    "*",  startLine);
                break;
            case '/':
                if (match('=')) add(TT.SLASHEQ, "/=", startLine);
                else            add(TT.SLASH,   "/",  startLine);
                break;
            case '%': add(TT.PERCENT,   "%",  startLine); break;
            case '&':
                if (match('&')) add(TT.AND, "&&", startLine);
                else            add(TT.AMP, "&",  startLine);
                break;
            case '|':
                if (match('|')) add(TT.OR,   "||", startLine);
                else            add(TT.PIPE, "|",  startLine);
                break;
            case '^': add(TT.CARET,  "^",  startLine); break;
            case '~': add(TT.TILDE,  "~",  startLine); break;
            case '!':
                if (match('=')) add(TT.NEQ,  "!=", startLine);
                else            add(TT.BANG, "!",  startLine);
                break;
            case '<':
                if      (match('<')) add(TT.LSHIFT, "<<", startLine);
                else if (match('=')) add(TT.LE,     "<=", startLine);
                else                 add(TT.LT,     "<",  startLine);
                break;
            case '>':
                if      (match('>')) add(TT.RSHIFT, ">>", startLine);
                else if (match('=')) add(TT.GE,     ">=", startLine);
                else                 add(TT.GT,     ">",  startLine);
                break;
            case '=':
                if (match('=')) add(TT.EQ,     "==", startLine);
                else            add(TT.ASSIGN, "=",  startLine);
                break;
            case '(': add(TT.LPAREN,    "(", startLine); break;
            case ')': add(TT.RPAREN,    ")", startLine); break;
            case '{': add(TT.LBRACE,    "{", startLine); break;
            case '}': add(TT.RBRACE,    "}", startLine); break;
            case '[': add(TT.LBRACKET,  "[", startLine); break;
            case ']': add(TT.RBRACKET,  "]", startLine); break;
            case ';': add(TT.SEMICOLON, ";", startLine); break;
            case ',': add(TT.COMMA,     ",", startLine); break;
            case ':': add(TT.COLON,     ":", startLine); break;
            default:
                // skip unknown characters
                break;
        }
    }

    private void add(TT type, String val, int line) {
        tokens.add(new Token(type, val, line));
    }

    private boolean isHexDigit(char c) {
        return Character.isDigit(c) ||
               (c >= 'a' && c <= 'f') ||
               (c >= 'A' && c <= 'F');
    }
}
