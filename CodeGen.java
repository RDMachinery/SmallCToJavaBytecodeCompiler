import java.util.*;
import java.io.*;

/**
 * Java bytecode emitter for Small-C.
 *
 * Generates a valid JVM .class file directly — no external libraries.
 * Each Small-C source file compiles to a single Java class.
 *
 * The JVM is a stack machine, which maps cleanly to C expression evaluation.
 * All Small-C int and char values are represented as JVM int (32-bit).
 * Arrays are represented as int[].
 *
 * Built-in functions supported:
 *   putchar(c)    - writes a character to stdout
 *   printf(fmt)   - simplified: prints a string literal
 *   exit(code)    - calls System.exit()
 */
public class CodeGen {

    // -----------------------------------------------------------------------
    // Symbol table
    // -----------------------------------------------------------------------

    enum SymKind { LOCAL, GLOBAL, ARRAY }

    static class Symbol {
        final String  name;
        final SymKind kind;
        final int     slot;      // local variable slot (locals only)
        final int     arraySize; // arrays only
        Symbol(String name, SymKind kind, int slot, int arraySize) {
            this.name = name; this.kind = kind;
            this.slot = slot; this.arraySize = arraySize;
        }
    }

    // -----------------------------------------------------------------------
    // Constant pool builder
    // -----------------------------------------------------------------------

    static class ConstantPool {
        private final List<byte[]> entries = new ArrayList<>();
        private final Map<Object, Integer> cache = new HashMap<>();

        public ConstantPool() {
            entries.add(null); // index 0 is unused in JVM constant pool
        }

        public int size() { return entries.size(); }

        private int add(byte[] entry, Object key) {
            Integer cached = cache.get(key);
            if (cached != null) return cached;
            int idx = entries.size();
            entries.add(entry);
            cache.put(key, idx);
            return idx;
        }

        public int addUtf8(String s) {
            try {
                byte[] bytes = s.getBytes("UTF-8");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                baos.write(1); // CONSTANT_Utf8
                baos.write((bytes.length >> 8) & 0xFF);
                baos.write(bytes.length & 0xFF);
                baos.write(bytes);
                return add(baos.toByteArray(), "utf8:" + s);
            } catch (IOException e) { throw new RuntimeException(e); }
        }

        public int addClass(String name) {
            int nameIdx = addUtf8(name);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(7); // CONSTANT_Class
            baos.write((nameIdx >> 8) & 0xFF);
            baos.write(nameIdx & 0xFF);
            return add(baos.toByteArray(), "class:" + name);
        }

        public int addNameAndType(String name, String desc) {
            int nameIdx = addUtf8(name);
            int descIdx = addUtf8(desc);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(12); // CONSTANT_NameAndType
            baos.write((nameIdx >> 8) & 0xFF);
            baos.write(nameIdx & 0xFF);
            baos.write((descIdx >> 8) & 0xFF);
            baos.write(descIdx & 0xFF);
            return add(baos.toByteArray(), "nat:" + name + ":" + desc);
        }

        public int addMethodRef(String clazz, String name, String desc) {
            int classIdx = addClass(clazz);
            int natIdx   = addNameAndType(name, desc);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(10); // CONSTANT_Methodref
            baos.write((classIdx >> 8) & 0xFF);
            baos.write(classIdx & 0xFF);
            baos.write((natIdx >> 8) & 0xFF);
            baos.write(natIdx & 0xFF);
            return add(baos.toByteArray(), "mref:" + clazz + ":" + name + ":" + desc);
        }

        public int addFieldRef(String clazz, String name, String desc) {
            int classIdx = addClass(clazz);
            int natIdx   = addNameAndType(name, desc);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(9); // CONSTANT_Fieldref
            baos.write((classIdx >> 8) & 0xFF);
            baos.write(classIdx & 0xFF);
            baos.write((natIdx >> 8) & 0xFF);
            baos.write(natIdx & 0xFF);
            return add(baos.toByteArray(), "fref:" + clazz + ":" + name + ":" + desc);
        }

        public int addString(String s) {
            int utf8Idx = addUtf8(s);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(8); // CONSTANT_String
            baos.write((utf8Idx >> 8) & 0xFF);
            baos.write(utf8Idx & 0xFF);
            return add(baos.toByteArray(), "str:" + s);
        }

        public int addInteger(int val) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(3); // CONSTANT_Integer
            baos.write((val >> 24) & 0xFF);
            baos.write((val >> 16) & 0xFF);
            baos.write((val >>  8) & 0xFF);
            baos.write( val        & 0xFF);
            return add(baos.toByteArray(), "int:" + val);
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeShort(entries.size()); // count includes unused index 0
            for (int i = 1; i < entries.size(); i++) {
                out.write(entries.get(i));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Bytecode builder
    // -----------------------------------------------------------------------

    static class BytecodeBuilder {
        private final ByteArrayOutputStream code = new ByteArrayOutputStream();
        // Pending patches: maps code offset -> label name
        private final Map<Integer, String> patches = new LinkedHashMap<>();
        // Resolved labels: label name -> code offset
        private final Map<String, Integer> labels  = new HashMap<>();
        private int labelCounter = 0;

        public int offset() { return code.size(); }

        public String newLabel() { return "L" + (labelCounter++); }
        public void defineLabel(String name) { labels.put(name, code.size()); }

        public void emit(int... bytes) {
            for (int b : bytes) code.write(b & 0xFF);
        }

        // Emit a branch instruction with a placeholder; returns patch site offset
        public void emitBranch(int opcode, String label) {
            int site = code.size();
            emit(opcode, 0, 0); // 2-byte relative offset placeholder
            patches.put(site, label);
        }

        public void patch(DataOutputStream out) throws IOException {
            byte[] bytes = code.toByteArray();
            for (Map.Entry<Integer, String> e : patches.entrySet()) {
                int    site  = e.getKey();
                String label = e.getValue();
                Integer target = labels.get(label);
                if (target == null)
                    throw new RuntimeException("Undefined label: " + label);
                int offset = target - site; // relative offset
                bytes[site + 1] = (byte)((offset >> 8) & 0xFF);
                bytes[site + 2] = (byte)( offset        & 0xFF);
            }
            out.write(bytes);
        }

        public int size() { return code.size(); }

        // Push int constant (chooses most compact opcode)
        public void pushInt(int val) {
            if      (val == -1)            emit(0x02);             // iconst_m1
            else if (val >= 0 && val <= 5) emit(0x03 + val);       // iconst_0..5
            else if (val >= -128 && val <= 127) { emit(0x10, val & 0xFF); } // bipush
            else if (val >= -32768 && val <= 32767) {               // sipush
                emit(0x11, (val >> 8) & 0xFF, val & 0xFF);
            } else {
                // Use ldc - caller must ensure constant is in pool
                // We emit ldc_w (wide) for safety
                emit(0x13, 0, 0); // placeholder - handled by caller
            }
        }

        public byte[] getBytes() { return code.toByteArray(); }
    }

    // -----------------------------------------------------------------------
    // Code generator
    // -----------------------------------------------------------------------

    private final String className;
    private final AST.Program program;
    private final ConstantPool cp = new ConstantPool();

    // Pre-populated constant pool entries
    private int cpThis, cpObject, cpSuperInit;
    private int cpSystem, cpOut, cpPrintStream;
    private int cpPrintChar, cpPrintStr, cpPrintInt;
    private int cpExit;

    // Global variables: name -> field descriptor index
    private final Map<String, String> globalTypes = new LinkedHashMap<>();

    // Per-function: local variable table
    private Map<String, Symbol> locals;
    private int nextSlot;

    // Break/continue label stacks
    private final Deque<String> breakLabels    = new ArrayDeque<>();
    private final Deque<String> continueLabels = new ArrayDeque<>();

    // String constants: value -> constant pool index
    private final Map<String, Integer> stringConsts = new LinkedHashMap<>();

    public CodeGen(String className, AST.Program program) {
        this.className = className;
        this.program   = program;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public byte[] generate() throws IOException {
        // Pre-scan globals so they're available during code generation
        for (AST.Declaration d : program.decls) {
            if (d instanceof AST.GlobalVar) {
                AST.GlobalVar gv = (AST.GlobalVar) d;
                globalTypes.put(gv.name, gv.arraySize > 0 ? "[I" : "I");
            }
        }

        // Pre-populate constant pool with commonly used entries
        cpThis        = cp.addClass(className);
        cpObject      = cp.addClass("java/lang/Object");
        cpSuperInit   = cp.addMethodRef("java/lang/Object", "<init>", "()V");
        cpSystem      = cp.addClass("java/lang/System");
        cpOut         = cp.addFieldRef("java/lang/System", "out", "Ljava/io/PrintStream;");
        cpPrintStream = cp.addClass("java/io/PrintStream");
        cpPrintChar   = cp.addMethodRef("java/io/PrintStream", "print", "(C)V");
        cpPrintStr    = cp.addMethodRef("java/io/PrintStream", "print", "(Ljava/lang/String;)V");
        cpPrintInt    = cp.addMethodRef("java/io/PrintStream", "print", "(I)V");
        cpExit        = cp.addMethodRef("java/lang/System", "exit", "(I)V");

        // Collect methods
        List<byte[]> methods = new ArrayList<>();
        methods.add(generateDefaultConstructor());

        // Generate static initialiser and methods
        List<byte[]> staticFields = new ArrayList<>();
        for (AST.Declaration d : program.decls) {
            if (d instanceof AST.FunctionDecl) {
                methods.add(generateMethod((AST.FunctionDecl) d));
            }
        }

        // Assemble class file
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        // Write class file structure after all cp entries are known
        return assembleClass(methods);
    }

    private byte[] assembleClass(List<byte[]> methods) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        // Magic + version (Java 6 = 50.0 - no stack map frames required)
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);   // minor
        out.writeShort(50);  // major = Java 6

        // Constant pool (write after all entries accumulated)
        cp.write(out);

        // Access flags: public + super
        out.writeShort(0x0021);

        // This class, super class
        out.writeShort(cpThis);
        out.writeShort(cpObject);

        // No interfaces
        out.writeShort(0);

        // Fields (globals as static int fields)
        List<byte[]> fields = buildFields();
        out.writeShort(fields.size());
        for (byte[] f : fields) out.write(f);

        // Methods
        out.writeShort(methods.size());
        for (byte[] m : methods) out.write(m);

        // No class attributes
        out.writeShort(0);

        return baos.toByteArray();
    }

    // -----------------------------------------------------------------------
    // Fields (global variables)
    // -----------------------------------------------------------------------

    private List<byte[]> buildFields() throws IOException {
        List<byte[]> fields = new ArrayList<>();
        for (AST.Declaration d : program.decls) {
            if (!(d instanceof AST.GlobalVar)) continue;
            AST.GlobalVar gv = (AST.GlobalVar) d;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            int nameIdx = cp.addUtf8(gv.name);
            String desc = gv.arraySize > 0 ? "[I" : "I";
            int descIdx = cp.addUtf8(desc);

            out.writeShort(0x0008); // ACC_STATIC
            out.writeShort(nameIdx);
            out.writeShort(descIdx);
            out.writeShort(0); // no attributes

            fields.add(baos.toByteArray());
        }
        return fields;
    }

    // -----------------------------------------------------------------------
    // Default constructor
    // -----------------------------------------------------------------------

    private byte[] generateDefaultConstructor() throws IOException {
        BytecodeBuilder bb = new BytecodeBuilder();
        bb.emit(0x2A);       // aload_0
        bb.emit(0xB7,
            (cpSuperInit >> 8) & 0xFF,
             cpSuperInit       & 0xFF); // invokespecial Object.<init>
        bb.emit(0xB1);       // return

        return buildMethod("<init>", "()V", 0x0001, bb, 1, 1);
    }

    // -----------------------------------------------------------------------
    // Method generation
    // -----------------------------------------------------------------------

    private byte[] generateMethod(AST.FunctionDecl fn) throws IOException {
        // Set up local variable table
        locals   = new LinkedHashMap<>();
        nextSlot = 0;

        // Parameters occupy first slots
        for (AST.Param p : fn.params) {
            locals.put(p.name, new Symbol(p.name, SymKind.LOCAL, nextSlot++, 0));
        }

        BytecodeBuilder bb = new BytecodeBuilder();

        // Initialise global arrays declared at top level
        // (done in <clinit> normally, but we handle in main for simplicity)
        if (fn.name.equals("main") && fn.params.isEmpty()) {
            generateGlobalArrayInits(bb);
        }

        generateBlock(fn.body, bb);

        // Ensure method ends with a return
        bb.emit(0xB1); // return (void)

        // Determine descriptor
        String desc = buildMethodDesc(fn);

        int accessFlags = 0x0009; // public static
        if (fn.name.equals("main") && fn.params.isEmpty()) {
            // main needs String[] arg
            desc = "([Ljava/lang/String;)V";
            accessFlags = 0x0009;
        }

        return buildMethod(fn.name, desc, accessFlags, bb,
                           Math.max(8, nextSlot + 4), nextSlot + 4);
    }

    private String buildMethodDesc(AST.FunctionDecl fn) {
        StringBuilder sb = new StringBuilder("(");
        for (AST.Param p : fn.params) sb.append("I");
        sb.append(")");
        sb.append(fn.returnType.equals("void") ? "V" : "I");
        return sb.toString();
    }

    private void generateGlobalArrayInits(BytecodeBuilder bb) throws IOException {
        for (AST.Declaration d : program.decls) {
            if (!(d instanceof AST.GlobalVar)) continue;
            AST.GlobalVar gv = (AST.GlobalVar) d;
            if (gv.arraySize > 0) {
                int fieldRef = cp.addFieldRef(className, gv.name, "[I");
                // newarray int, size
                bb.pushInt(gv.arraySize);
                bb.emit(0xBC, 10); // newarray T_INT
                bb.emit(0xB3,
                    (fieldRef >> 8) & 0xFF,
                     fieldRef       & 0xFF); // putstatic
            }
        }
    }

    private byte[] buildMethod(String name, String desc, int accessFlags,
                                BytecodeBuilder bb, int maxStack, int maxLocals)
            throws IOException {

        int nameIdx = cp.addUtf8(name);
        int descIdx = cp.addUtf8(desc);
        int codeIdx = cp.addUtf8("Code");

        // Build code attribute
        ByteArrayOutputStream codeAttr = new ByteArrayOutputStream();
        DataOutputStream codeOut = new DataOutputStream(codeAttr);
        codeOut.writeShort(maxStack);
        codeOut.writeShort(maxLocals);

        // Write patched bytecode
        ByteArrayOutputStream codeBytes = new ByteArrayOutputStream();
        DataOutputStream codeBytesOut = new DataOutputStream(codeBytes);
        bb.patch(codeBytesOut);

        codeOut.writeInt(codeBytes.size());
        codeOut.write(codeBytes.toByteArray());
        codeOut.writeShort(0); // exception table length
        codeOut.writeShort(0); // code attributes

        byte[] codeAttrBytes = codeAttr.toByteArray();

        // Build method
        ByteArrayOutputStream method = new ByteArrayOutputStream();
        DataOutputStream mout = new DataOutputStream(method);
        mout.writeShort(accessFlags);
        mout.writeShort(nameIdx);
        mout.writeShort(descIdx);
        mout.writeShort(1); // one attribute: Code
        mout.writeShort(codeIdx);
        mout.writeInt(codeAttrBytes.length);
        mout.write(codeAttrBytes);

        return method.toByteArray();
    }

    // -----------------------------------------------------------------------
    // Statement generation
    // -----------------------------------------------------------------------

    private void generateBlock(AST.Block block, BytecodeBuilder bb) throws IOException {
        for (AST.Stmt s : block.stmts) generateStmt(s, bb);
    }

    private void generateStmt(AST.Stmt s, BytecodeBuilder bb) throws IOException {
        if (s instanceof AST.Block)       { generateBlock((AST.Block)s, bb); return; }
        if (s instanceof AST.EmptyStmt)   { return; }
        if (s instanceof AST.ExprStmt)    { generateExpr(((AST.ExprStmt)s).expr, bb); bb.emit(0x57); return; } // pop
        if (s instanceof AST.VarDecl)     { generateVarDecl((AST.VarDecl)s, bb); return; }
        if (s instanceof AST.IfStmt)      { generateIf((AST.IfStmt)s, bb); return; }
        if (s instanceof AST.WhileStmt)   { generateWhile((AST.WhileStmt)s, bb); return; }
        if (s instanceof AST.DoWhileStmt) { generateDoWhile((AST.DoWhileStmt)s, bb); return; }
        if (s instanceof AST.ForStmt)     { generateFor((AST.ForStmt)s, bb); return; }
        if (s instanceof AST.ReturnStmt)  { generateReturn((AST.ReturnStmt)s, bb); return; }
        if (s instanceof AST.BreakStmt)   {
            if (breakLabels.isEmpty()) throw new RuntimeException("break outside loop");
            bb.emitBranch(0xA7, breakLabels.peek()); // goto
            return;
        }
        if (s instanceof AST.ContinueStmt) {
            if (continueLabels.isEmpty()) throw new RuntimeException("continue outside loop");
            bb.emitBranch(0xA7, continueLabels.peek()); // goto
            return;
        }
        throw new RuntimeException("Unknown statement: " + s.getClass());
    }

    private void generateVarDecl(AST.VarDecl v, BytecodeBuilder bb) throws IOException {
        int slot = nextSlot++;
        if (v.arraySize > 0) {
            // Allocate int array
            bb.pushInt(v.arraySize);
            bb.emit(0xBC, 10); // newarray T_INT
            storeLocal(slot, bb);
            locals.put(v.name, new Symbol(v.name, SymKind.ARRAY, slot, v.arraySize));
        } else {
            locals.put(v.name, new Symbol(v.name, SymKind.LOCAL, slot, 0));
            if (v.init != null) {
                generateExpr(v.init, bb);
                storeLocal(slot, bb);
            } else {
                bb.emit(0x03); // iconst_0
                storeLocal(slot, bb);
            }
        }
    }

    private void generateIf(AST.IfStmt s, BytecodeBuilder bb) throws IOException {
        String elseLabel = bb.newLabel();
        String endLabel  = bb.newLabel();

        generateExpr(s.cond, bb);
        bb.emitBranch(0x99, elseLabel); // ifeq -> else

        generateStmt(s.then, bb);

        if (s.else_ != null) {
            bb.emitBranch(0xA7, endLabel); // goto end
            bb.defineLabel(elseLabel);
            generateStmt(s.else_, bb);
            bb.defineLabel(endLabel);
        } else {
            bb.defineLabel(elseLabel);
        }
    }

    private void generateWhile(AST.WhileStmt s, BytecodeBuilder bb) throws IOException {
        String loopLabel  = bb.newLabel();
        String breakLabel = bb.newLabel();

        bb.defineLabel(loopLabel);
        breakLabels.push(breakLabel);
        continueLabels.push(loopLabel);

        generateExpr(s.cond, bb);
        bb.emitBranch(0x99, breakLabel); // ifeq -> exit

        generateStmt(s.body, bb);
        bb.emitBranch(0xA7, loopLabel); // goto loop

        bb.defineLabel(breakLabel);
        breakLabels.pop();
        continueLabels.pop();
    }

    private void generateDoWhile(AST.DoWhileStmt s, BytecodeBuilder bb) throws IOException {
        String loopLabel  = bb.newLabel();
        String breakLabel = bb.newLabel();

        bb.defineLabel(loopLabel);
        breakLabels.push(breakLabel);
        continueLabels.push(loopLabel);

        generateStmt(s.body, bb);
        generateExpr(s.cond, bb);
        bb.emitBranch(0x9A, loopLabel); // ifne -> loop

        bb.defineLabel(breakLabel);
        breakLabels.pop();
        continueLabels.pop();
    }

    private void generateFor(AST.ForStmt s, BytecodeBuilder bb) throws IOException {
        String loopLabel     = bb.newLabel();
        String continueLabel = bb.newLabel();
        String breakLabel    = bb.newLabel();

        if (s.init != null) generateStmt(s.init, bb);

        bb.defineLabel(loopLabel);
        if (s.cond != null) {
            generateExpr(s.cond, bb);
            bb.emitBranch(0x99, breakLabel); // ifeq -> exit
        }

        breakLabels.push(breakLabel);
        continueLabels.push(continueLabel);

        generateStmt(s.body, bb);

        bb.defineLabel(continueLabel);
        if (s.update != null) {
            generateExpr(s.update, bb);
            bb.emit(0x57); // pop
        }
        bb.emitBranch(0xA7, loopLabel); // goto loop

        bb.defineLabel(breakLabel);
        breakLabels.pop();
        continueLabels.pop();
    }

    private void generateReturn(AST.ReturnStmt s, BytecodeBuilder bb) throws IOException {
        if (s.value != null) {
            generateExpr(s.value, bb);
            bb.emit(0xAC); // ireturn
        } else {
            bb.emit(0xB1); // return
        }
    }

    // -----------------------------------------------------------------------
    // Expression generation
    // -----------------------------------------------------------------------

    private void generateExpr(AST.Expr e, BytecodeBuilder bb) throws IOException {

        if (e instanceof AST.IntLit) {
            int val = ((AST.IntLit)e).value;
            pushIntConst(val, bb);
            return;
        }

        if (e instanceof AST.CharLit) {
            pushIntConst(((AST.CharLit)e).value, bb);
            return;
        }

        if (e instanceof AST.StringLit) {
            String s = ((AST.StringLit)e).value;
            int idx = cp.addString(s);
            bb.emit(0x13, (idx >> 8) & 0xFF, idx & 0xFF); // ldc_w
            return;
        }

        if (e instanceof AST.VarRef) {
            loadVar(((AST.VarRef)e).name, bb);
            return;
        }

        if (e instanceof AST.ArrayRef) {
            AST.ArrayRef ar = (AST.ArrayRef)e;
            loadArrayRef(ar.name, bb);    // load array reference
            generateExpr(ar.index, bb);   // load index
            bb.emit(0x2E);                // iaload
            return;
        }

        if (e instanceof AST.Assign) {
            generateAssign((AST.Assign)e, bb);
            return;
        }

        if (e instanceof AST.BinOp) {
            generateBinOp((AST.BinOp)e, bb);
            return;
        }

        if (e instanceof AST.UnaryOp) {
            generateUnary((AST.UnaryOp)e, bb);
            return;
        }

        if (e instanceof AST.PreIncDec) {
            generatePreIncDec((AST.PreIncDec)e, bb);
            return;
        }

        if (e instanceof AST.PostIncDec) {
            generatePostIncDec((AST.PostIncDec)e, bb);
            return;
        }

        if (e instanceof AST.CallExpr) {
            generateCall((AST.CallExpr)e, bb);
            return;
        }

        throw new RuntimeException("Unknown expression: " + e.getClass());
    }

    private void pushIntConst(int val, BytecodeBuilder bb) throws IOException {
        if      (val == -1)              bb.emit(0x02);
        else if (val >= 0 && val <= 5)   bb.emit(0x03 + val);
        else if (val >= -128 && val <= 127) bb.emit(0x10, val & 0xFF);
        else if (val >= -32768 && val <= 32767) bb.emit(0x11, (val>>8)&0xFF, val&0xFF);
        else {
            int idx = cp.addInteger(val);
            bb.emit(0x13, (idx>>8)&0xFF, idx&0xFF); // ldc_w
        }
    }

    private void loadVar(String name, BytecodeBuilder bb) throws IOException {
        Symbol sym = resolveSymbol(name);
        if (sym.kind == SymKind.LOCAL || sym.kind == SymKind.ARRAY) {
            loadLocal(sym.slot, bb);
        } else {
            // global scalar
            int fref = cp.addFieldRef(className, name, "I");
            bb.emit(0xB2, (fref>>8)&0xFF, fref&0xFF); // getstatic
        }
    }

    private void loadArrayRef(String name, BytecodeBuilder bb) throws IOException {
        Symbol sym = resolveSymbol(name);
        if (sym != null && (sym.kind == SymKind.ARRAY || sym.kind == SymKind.LOCAL)) {
            loadLocal(sym.slot, bb);
        } else {
            int fref = cp.addFieldRef(className, name, "[I");
            bb.emit(0xB2, (fref>>8)&0xFF, fref&0xFF); // getstatic
        }
    }

    private void storeVar(String name, BytecodeBuilder bb) throws IOException {
        Symbol sym = resolveSymbol(name);
        if (sym != null && sym.kind == SymKind.LOCAL) {
            storeLocal(sym.slot, bb);
        } else {
            int fref = cp.addFieldRef(className, name, "I");
            bb.emit(0xB3, (fref>>8)&0xFF, fref&0xFF); // putstatic
        }
    }

    private void loadLocal(int slot, BytecodeBuilder bb) {
        switch (slot) {
            case 0: bb.emit(0x1A); break; // iload_0
            case 1: bb.emit(0x1B); break; // iload_1
            case 2: bb.emit(0x1C); break; // iload_2
            case 3: bb.emit(0x1D); break; // iload_3
            default: bb.emit(0x15, slot); break; // iload
        }
    }

    private void storeLocal(int slot, BytecodeBuilder bb) {
        switch (slot) {
            case 0: bb.emit(0x3B); break; // istore_0
            case 1: bb.emit(0x3C); break; // istore_1
            case 2: bb.emit(0x3D); break; // istore_2
            case 3: bb.emit(0x3E); break; // istore_3
            default: bb.emit(0x36, slot); break; // istore
        }
    }

    // aload (for arrays)
    private void aloadLocal(int slot, BytecodeBuilder bb) {
        switch (slot) {
            case 0: bb.emit(0x2A); break;
            case 1: bb.emit(0x2B); break;
            case 2: bb.emit(0x2C); break;
            case 3: bb.emit(0x2D); break;
            default: bb.emit(0x19, slot); break;
        }
    }

    private Symbol resolveSymbol(String name) {
        if (locals != null) {
            Symbol s = locals.get(name);
            if (s != null) return s;
        }
        if (globalTypes.containsKey(name)) {
            return new Symbol(name, SymKind.GLOBAL, -1, 0);
        }
        return null;
    }

    private void generateAssign(AST.Assign a, BytecodeBuilder bb) throws IOException {
        if (a.target instanceof AST.ArrayRef) {
            AST.ArrayRef ar = (AST.ArrayRef) a.target;
            loadArrayRef(ar.name, bb);
            generateExpr(ar.index, bb);
            if (!a.op.equals("=")) {
                // dup array ref and index for compound assign
                bb.emit(0x59); // dup
                // Need to duplicate array+index: dup2
                bb.emit(0x5C); // dup2 -- [arr, idx, arr, idx]
                bb.emit(0x2E); // iaload -- [arr, idx, oldval]
                generateExpr(a.value, bb);
                emitArithOp(a.op.replace("=",""), bb);
            } else {
                generateExpr(a.value, bb);
            }
            bb.emit(0x4F); // iastore
            // Assignment is an expression; push 0 as value (simplified)
            bb.emit(0x03);
        } else {
            String name = ((AST.VarRef)a.target).name;
            if (!a.op.equals("=")) {
                loadVar(name, bb);
                generateExpr(a.value, bb);
                emitArithOp(a.op.replace("=",""), bb);
            } else {
                generateExpr(a.value, bb);
            }
            // Dup before store so assignment expression has a value
            bb.emit(0x59); // dup
            storeVar(name, bb);
        }
    }

    private void emitArithOp(String op, BytecodeBuilder bb) {
        switch (op) {
            case "+": bb.emit(0x60); break; // iadd
            case "-": bb.emit(0x64); break; // isub
            case "*": bb.emit(0x68); break; // imul
            case "/": bb.emit(0x6C); break; // idiv
            case "%": bb.emit(0x70); break; // irem
            case "&": bb.emit(0x7E); break; // iand
            case "|": bb.emit(0x80); break; // ior
            case "^": bb.emit(0x82); break; // ixor
            case "<<":bb.emit(0x78); break; // ishl
            case ">>":bb.emit(0x7A); break; // ishr
        }
    }

    private void generateBinOp(AST.BinOp e, BytecodeBuilder bb) throws IOException {
        String op = e.op;

        // Short-circuit operators
        if (op.equals("&&")) { generateLogicalAnd(e, bb); return; }
        if (op.equals("||")) { generateLogicalOr(e, bb);  return; }

        // Relational operators — produce 0 or 1
        if (op.equals("==") || op.equals("!=") ||
            op.equals("<")  || op.equals("<=") ||
            op.equals(">")  || op.equals(">=")) {
            generateRelational(e, bb);
            return;
        }

        generateExpr(e.left,  bb);
        generateExpr(e.right, bb);
        emitArithOp(op, bb);
    }

    private void generateRelational(AST.BinOp e, BytecodeBuilder bb) throws IOException {
        generateExpr(e.left,  bb);
        generateExpr(e.right, bb);
        // isub and compare to 0
        String trueLabel = bb.newLabel();
        String endLabel  = bb.newLabel();
        int opcode;
        switch (e.op) {
            case "==": opcode = 0x9F; break; // if_icmpeq
            case "!=": opcode = 0xA0; break; // if_icmpne
            case "<":  opcode = 0xA1; break; // if_icmplt
            case ">=": opcode = 0xA2; break; // if_icmpge
            case ">":  opcode = 0xA3; break; // if_icmpgt
            case "<=": opcode = 0xA4; break; // if_icmple
            default:   opcode = 0x9F;
        }
        bb.emitBranch(opcode, trueLabel);
        bb.emit(0x03); // iconst_0 (false)
        bb.emitBranch(0xA7, endLabel);
        bb.defineLabel(trueLabel);
        bb.emit(0x04); // iconst_1 (true)
        bb.defineLabel(endLabel);
    }

    private void generateLogicalAnd(AST.BinOp e, BytecodeBuilder bb) throws IOException {
        String falseLabel = bb.newLabel();
        String endLabel   = bb.newLabel();
        generateExpr(e.left, bb);
        bb.emitBranch(0x99, falseLabel); // ifeq -> false
        generateExpr(e.right, bb);
        bb.emitBranch(0x99, falseLabel); // ifeq -> false
        bb.emit(0x04); // iconst_1
        bb.emitBranch(0xA7, endLabel);
        bb.defineLabel(falseLabel);
        bb.emit(0x03); // iconst_0
        bb.defineLabel(endLabel);
    }

    private void generateLogicalOr(AST.BinOp e, BytecodeBuilder bb) throws IOException {
        String trueLabel = bb.newLabel();
        String endLabel  = bb.newLabel();
        generateExpr(e.left, bb);
        bb.emitBranch(0x9A, trueLabel); // ifne -> true
        generateExpr(e.right, bb);
        bb.emitBranch(0x9A, trueLabel); // ifne -> true
        bb.emit(0x03); // iconst_0
        bb.emitBranch(0xA7, endLabel);
        bb.defineLabel(trueLabel);
        bb.emit(0x04); // iconst_1
        bb.defineLabel(endLabel);
    }

    private void generateUnary(AST.UnaryOp e, BytecodeBuilder bb) throws IOException {
        generateExpr(e.operand, bb);
        switch (e.op) {
            case "-": bb.emit(0x74); break; // ineg
            case "~":
                // ~x == x ^ -1
                bb.emit(0x02); // iconst_m1
                bb.emit(0x82); // ixor
                break;
            case "!":
                // !x: if x==0 push 1 else push 0
                String trueL = bb.newLabel();
                String endL  = bb.newLabel();
                bb.emitBranch(0x99, trueL); // ifeq
                bb.emit(0x03);
                bb.emitBranch(0xA7, endL);
                bb.defineLabel(trueL);
                bb.emit(0x04);
                bb.defineLabel(endL);
                break;
        }
    }

    private void generatePreIncDec(AST.PreIncDec e, BytecodeBuilder bb) throws IOException {
        String name = ((AST.VarRef)e.target).name;
        loadVar(name, bb);
        bb.emit(0x04); // iconst_1
        bb.emit(e.op.equals("++") ? 0x60 : 0x64); // iadd or isub
        bb.emit(0x59); // dup
        storeVar(name, bb);
    }

    private void generatePostIncDec(AST.PostIncDec e, BytecodeBuilder bb) throws IOException {
        String name = ((AST.VarRef)e.target).name;
        loadVar(name, bb);
        bb.emit(0x59); // dup (save old value)
        bb.emit(0x04); // iconst_1
        bb.emit(e.op.equals("++") ? 0x60 : 0x64);
        storeVar(name, bb);
        // old value is still on stack
    }

    private void generateCall(AST.CallExpr e, BytecodeBuilder bb) throws IOException {
        // Built-in: putchar(c)
        if (e.name.equals("putchar")) {
            bb.emit(0xB2, (cpOut>>8)&0xFF, cpOut&0xFF); // getstatic System.out
            generateExpr(e.args.get(0), bb);
            bb.emit(0x91); // i2c (int to char)
            bb.emit(0xB6,
                (cpPrintChar>>8)&0xFF,
                 cpPrintChar    &0xFF); // invokevirtual PrintStream.print(C)
            bb.emit(0x03); // iconst_0 as return value
            return;
        }

        // Built-in: printf / puts (simplified - string or int args)
        if (e.name.equals("printf") || e.name.equals("puts")) {
            bb.emit(0xB2, (cpOut>>8)&0xFF, cpOut&0xFF);
            generateExpr(e.args.get(0), bb);
            AST.Expr arg0 = e.args.get(0);
            if (arg0 instanceof AST.StringLit) {
                bb.emit(0xB6, (cpPrintStr>>8)&0xFF, cpPrintStr&0xFF);
            } else {
                bb.emit(0xB6, (cpPrintInt>>8)&0xFF, cpPrintInt&0xFF);
            }
            bb.emit(0x03);
            return;
        }

        // Built-in: exit(code)
        if (e.name.equals("exit")) {
            generateExpr(e.args.get(0), bb);
            bb.emit(0xB8, (cpExit>>8)&0xFF, cpExit&0xFF); // invokestatic
            bb.emit(0x03);
            return;
        }

        // User-defined function call (all args are int)
        for (AST.Expr arg : e.args) generateExpr(arg, bb);
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < e.args.size(); i++) desc.append("I");
        desc.append(")I");
        int mref = cp.addMethodRef(className, e.name, desc.toString());
        bb.emit(0xB8, (mref>>8)&0xFF, mref&0xFF); // invokestatic
    }
}
