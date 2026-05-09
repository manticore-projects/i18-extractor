package com.manticore.i18n;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a CompilationUnit, finds user-facing string expressions inside Swing UI sinks,
 * extracts them to a bundle, and rewrites the call site as I18n.tr("key", args...).
 *
 * Supported input shapes:
 *   - String literal
 *   - + concatenation chain (with arbitrary nesting, parens, mixed expressions)
 *   - String.format(...)         (including positional %1$s, %d, %f, %n, %%)
 *   - MessageFormat.format(...)  (passthrough)
 *   - new StringBuilder().append(...).append(...).toString()
 *   - new StringBuffer() variants of the above
 *   - Multi-statement local StringBuilder/StringBuffer used linearly
 *   - Methods returning a String pattern, where the pattern is "substantial"
 *     (>= 2 newlines or >= 100 chars) — covers welcome/help/about/license helpers
 *
 * Nested combinations work: any of the above forms may appear as an operand of any
 * other; the extractor splices patterns and remaps argument indices so the bundle
 * sees one merged MessageFormat pattern.
 */
public final class Extractor {

    /** Method names whose String arguments are user-facing UI text. */
    private static final Set<String> UI_METHODS = Set.of(
        // text-bearing setters
        "setText", "setTitle", "setToolTipText", "setLabel",
        "setApproveButtonText", "setApproveButtonToolTipText", "setDialogTitle",
        "setNote",
        // dialogs
        "showMessageDialog", "showConfirmDialog", "showInputDialog", "showOptionDialog",
        "showInternalMessageDialog", "showInternalConfirmDialog",
        "showInternalInputDialog", "showInternalOptionDialog",
        // borders
        "createTitledBorder", "createEtchedBorder",
        // tabs / nodes
        "addTab", "insertTab", "setTitleAt"
    );

    /** Sealed shape: a piece of a + concatenation. */
    sealed interface Part {
        record Literal(String text) implements Part {}
        record Expr(Expression expression) implements Part {}
    }

    /** Result of extracting a single translatable expression. */
    record Translatable(String pattern, List<Expression> args, List<Node> cleanup) {
        Translatable(String pattern, List<Expression> args) {
            this(pattern, args, List.of());
        }
    }

    private static final Pattern PRINTF_SPEC = Pattern.compile(
        "%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");
    private static final Pattern MF_PLACEHOLDER = Pattern.compile("\\{(\\d+)([,}])");

    /**
     * Built-in UI constructor patterns. Maps simple class name → array of arg
     * indices that hold user-facing text. Extended at runtime via --ui-constructor.
     *
     * Each entry is interpreted as: when seeing {@code new <ClassName>(...args...)},
     * extract the args at the listed positions (provided they're translatable
     * expressions per the existing dispatcher; non-String args at listed positions
     * are silently skipped).
     *
     * Coverage: standard javax.swing classes whose constructors take user-facing
     * text. Deliberately excludes JTextField, JTextArea, JEditorPane, JPasswordField,
     * JComboBox, JList, JTable, JFileChooser — their String args are usually data
     * values, not UI labels. Register those per-project via --ui-constructor if you
     * need them.
     *
     * AWT counterparts (Frame, Dialog, Label, Button, etc.) deliberately excluded:
     * the simple names collide with user-defined classes too often.
     */
    private static final Map<String, int[]> BUILTIN_UI_CTORS = Map.ofEntries(
        // Buttons and toggles
        Map.entry("JButton",                new int[]{0}),
        Map.entry("JCheckBox",              new int[]{0}),
        Map.entry("JCheckBoxMenuItem",      new int[]{0}),
        Map.entry("JRadioButton",           new int[]{0}),
        Map.entry("JRadioButtonMenuItem",   new int[]{0}),
        Map.entry("JToggleButton",          new int[]{0}),
        // Labels and menus
        Map.entry("JLabel",                 new int[]{0}),
        Map.entry("JMenu",                  new int[]{0}),
        Map.entry("JMenuItem",              new int[]{0}),
        Map.entry("JPopupMenu",             new int[]{0}),
        // Top-level windows / panes
        Map.entry("JFrame",                 new int[]{0}),
        Map.entry("JInternalFrame",         new int[]{0}),
        Map.entry("JOptionPane",            new int[]{0}),
        Map.entry("JDialog",                new int[]{0, 1}),     // (String title) or (Owner, String title)
        // Tool bars
        Map.entry("JToolBar",               new int[]{0}),
        // Actions
        Map.entry("AbstractAction",         new int[]{0}),
        // Borders
        Map.entry("TitledBorder",           new int[]{0, 1}),     // (String) or (Border, String)
        // Progress monitor
        Map.entry("ProgressMonitor",        new int[]{1, 2})      // (parent, message, note, ...)
    );

    private final Map<String, String> bundle;
    private final Map<String, String> reverseBundle = new HashMap<>();
    private final String helperPackage;
    private final Map<String, int[]> uiConstructors;
    private int totalReplacements = 0;

    // Per-file state, reset on each processFile() call
    private String currentClass = "";
    private Set<Node> nodesToRemove = new HashSet<>();

    public Extractor(Map<String, String> bundle, String helperPackage) {
        this(bundle, helperPackage, Map.of());
    }

    public Extractor(Map<String, String> bundle, String helperPackage,
                     Map<String, int[]> additionalUiConstructors) {
        this.bundle = bundle;
        this.helperPackage = helperPackage;
        Map<String, int[]> merged = new HashMap<>(BUILTIN_UI_CTORS);
        merged.putAll(additionalUiConstructors);
        this.uiConstructors = Map.copyOf(merged);
        bundle.forEach((k, v) -> reverseBundle.put(v, k));
    }

    public int totalReplacements() {
        return totalReplacements;
    }

    // -- File-level orchestration ---------------------------------------------------

    public void processFile(Path file, CompilationUnit cu) {
        currentClass = cu.getPrimaryTypeName()
            .orElseGet(() -> file.getFileName().toString().replaceFirst("\\.java$", ""));
        nodesToRemove = new HashSet<>();

        int beforeReplacements = totalReplacements;

        // Pass 1: UI method-call arguments.
        // Snapshot first — we'll mutate the tree as we go.
        List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
        for (MethodCallExpr call : calls) {
            if (call.getParentNode().isEmpty()) continue;          // detached by prior replace
            if (!UI_METHODS.contains(call.getNameAsString())) continue;

            // Each arg evaluated independently
            for (int i = 0; i < call.getArguments().size(); i++) {
                Expression arg = call.getArgument(i);
                processArgument(arg, call);
            }
        }

        // Pass 2: methods returning substantial String text (welcome screens, help,
        // license notices, about boxes — common Swing patterns where the UI sink is
        // somewhere else but the pattern lives in a String getter).
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            processMethodReturn(method);
        }

        // Pass 3: constructor arguments to known UI types — `new JButton("...")`,
        // `new JLabel("...")`, `new Action(id, label, icon, tooltip)`, etc.
        // The position list is class-specific (configured via --ui-constructor or
        // BUILTIN_UI_CTORS). Args at non-listed positions are left alone.
        for (ObjectCreationExpr ctor : cu.findAll(ObjectCreationExpr.class)) {
            if (ctor.getParentNode().isEmpty()) continue;
            int[] indices = uiConstructors.get(ctor.getTypeAsString());
            if (indices == null) continue;
            NodeList<Expression> args = ctor.getArguments();
            for (int idx : indices) {
                if (idx < 0 || idx >= args.size()) continue;
                Expression arg = args.get(idx);
                if (arg.getParentNode().isEmpty()) continue;     // already replaced
                processArgument(arg, null);
            }
        }

        // Pass 4: static `Object[][]` field initializers — common Swing menu/toolbar
        // pattern where each row is `{categoryLabel, new SomeAction[]{...}}`.
        // Conservative: only extract the row's first String literal IF the row also
        // contains an array creation of a registered UI constructor type, so plain
        // data tables don't get caught.
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            if (!field.isStatic()) continue;
            for (VariableDeclarator var : field.getVariables()) {
                if (!"Object[][]".equals(var.getTypeAsString())) continue;
                ArrayInitializerExpr outer = unwrapInitializer(var.getInitializer().orElse(null));
                if (outer == null) continue;
                for (Expression rowExpr : outer.getValues()) {
                    if (!(rowExpr instanceof ArrayInitializerExpr row)) continue;
                    if (row.getValues().isEmpty()) continue;
                    Expression first = row.getValues().get(0);
                    if (!(first instanceof StringLiteralExpr)) continue;
                    if (!rowContainsRegisteredUIArray(row)) continue;
                    if (first.getParentNode().isEmpty()) continue;
                    processArgument(first, null);
                }
            }
        }

        // Commit deferred deletions (multi-statement StringBuilder cleanup)
        for (Node n : nodesToRemove) {
            if (n.getParentNode().isPresent()) n.remove();
        }

        // Add the I18n import if we modified anything in this file
        if (totalReplacements > beforeReplacements) {
            String fqn = helperPackage + ".I18n";
            boolean alreadyImported = cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(fqn));
            if (!alreadyImported) cu.addImport(fqn);
        }
    }

    /** Unwrap either a bare ArrayInitializerExpr or `new T[][]{...}` to the inner initializer. */
    private static ArrayInitializerExpr unwrapInitializer(Expression e) {
        if (e == null) return null;
        if (e instanceof ArrayInitializerExpr a) return a;
        if (e instanceof ArrayCreationExpr ac) return ac.getInitializer().orElse(null);
        return null;
    }

    /** Does the array row contain `new SomeRegisteredUIType[]{...}` somewhere? */
    private boolean rowContainsRegisteredUIArray(ArrayInitializerExpr row) {
        for (Expression v : row.getValues()) {
            if (v instanceof ArrayCreationExpr ac
                && uiConstructors.containsKey(ac.getElementType().asString())) {
                return true;
            }
        }
        return false;
    }

    // -- Per-argument processing (Pass 1) -------------------------------------------

    private void processArgument(Expression arg, MethodCallExpr context) {
        Translatable t = extractTranslatable(arg);
        if (t == null) return;
        apply(arg, t, null);
    }

    // -- Method-return processing (Pass 2) ------------------------------------------

    /** Methods we never want to extract from regardless of content. */
    private static final Set<String> SKIP_METHOD_NAMES = Set.of(
        "toString", "hashCode", "equals", "clone",
        "getClass", "getName", "getId", "getKey", "getCode"
    );

    private void processMethodReturn(MethodDeclaration method) {
        if (!"String".equals(method.getTypeAsString())) return;
        if (SKIP_METHOD_NAMES.contains(method.getNameAsString())) return;

        for (ReturnStmt ret : method.findAll(ReturnStmt.class)) {
            if (ret.getExpression().isEmpty()) continue;
            // Skip returns that belong to a nested lambda — their target isn't this method.
            if (insideLambda(ret, method)) continue;

            Expression expr = ret.getExpression().get();
            if (expr.getParentNode().isEmpty()) continue;       // already replaced
            Translatable t = extractTranslatable(expr);
            if (t == null) continue;
            if (!isSubstantialText(t.pattern())) continue;

            // Prefer ClassName.methodName as the key base — it's the natural identifier
            String preferred = currentClass + "." + method.getNameAsString();
            apply(expr, t, preferred);
        }
    }

    private static boolean insideLambda(Node from, Node target) {
        Node cur = from.getParentNode().orElse(null);
        while (cur != null && cur != target) {
            if (cur instanceof LambdaExpr) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * Heuristic for "this looks like real user-facing text inside a getter".
     * Tightened on top of shouldExtract to avoid pulling in toString-style returns,
     * SQL builders, and exception messages — those tend to be one-line and short.
     */
    private static boolean isSubstantialText(String pattern) {
        if (pattern == null) return false;
        long newlines = pattern.chars().filter(c -> c == '\n').count();
        return newlines >= 2 || pattern.length() >= 100;
    }

    // -- Shared replacement core ---------------------------------------------------

    /**
     * Commit an extraction: add to bundle (or reuse existing key), replace the
     * target expression with I18n.tr(key, args...), queue any cleanup nodes.
     *
     * @param target       the AST node to replace
     * @param t            the extracted Translatable
     * @param preferredKey optional preferred key base (e.g. "AccountPanel.welcome");
     *                     if null, a slug is generated from the pattern text
     */
    private void apply(Expression target, Translatable t, String preferredKey) {
        if (!shouldExtract(t.pattern())) return;

        String key = makeKey(currentClass, t.pattern(), preferredKey);
        if (!bundle.containsKey(key)) {
            bundle.put(key, t.pattern());
            reverseBundle.put(t.pattern(), key);
        }

        NodeList<Expression> callArgs = new NodeList<>();
        callArgs.add(new StringLiteralExpr(key));
        for (Expression e : t.args()) callArgs.add(e.clone());

        MethodCallExpr replacement = new MethodCallExpr(new NameExpr("I18n"), "tr", callArgs);
        target.replace(replacement);

        nodesToRemove.addAll(t.cleanup());
        totalReplacements++;
    }

    // -- Dispatcher -----------------------------------------------------------------

    private Translatable extractTranslatable(Expression e) {
        if (e instanceof MethodCallExpr call) {
            if (isStringFormat(call))         return fromStringFormat(call);
            if (isMessageFormat(call))        return fromMessageFormat(call);
            if (isStringBuilderToString(call)) return fromStringBuilder(call);
        }
        // Fallback: literal or + concatenation
        List<Part> parts = new ArrayList<>();
        flattenInto(e, parts);
        return build(parts);
    }

    /** For nested splicing: only recognises forms that are pre-built patterns. */
    private Translatable tryExtractNested(Expression e) {
        if (!(e instanceof MethodCallExpr call)) return null;
        if (isStringFormat(call))         return fromStringFormat(call);
        if (isMessageFormat(call))        return fromMessageFormat(call);
        if (isStringBuilderToString(call)) return fromStringBuilder(call);
        return null;
    }

    // -- + concatenation ------------------------------------------------------------

    private void flattenInto(Expression e, List<Part> parts) {
        if (e instanceof EnclosedExpr enc) {
            flattenInto(enc.getInner(), parts);
            return;
        }
        if (e instanceof BinaryExpr bin
                && bin.getOperator() == BinaryExpr.Operator.PLUS
                && bin.findFirst(StringLiteralExpr.class).isPresent()) {
            flattenInto(bin.getLeft(), parts);
            flattenInto(bin.getRight(), parts);
            return;
        }
        if (e instanceof StringLiteralExpr lit) {
            parts.add(new Part.Literal(lit.asString()));
            return;
        }
        parts.add(new Part.Expr(e));
    }

    private Translatable build(List<Part> parts) {
        // Must contain at least one user-facing literal somewhere
        boolean anyLiteral = parts.stream().anyMatch(p -> p instanceof Part.Literal);
        if (!anyLiteral) return null;

        boolean hasArgs = parts.stream().anyMatch(p -> p instanceof Part.Expr);
        StringBuilder pattern = new StringBuilder();
        List<Expression> args = new ArrayList<>();

        for (Part p : parts) {
            if (p instanceof Part.Literal lit) {
                pattern.append(hasArgs ? escapeMF(lit.text()) : lit.text());
            } else if (p instanceof Part.Expr e) {
                Translatable inner = tryExtractNested(e.expression());
                if (inner != null) {
                    pattern.append(rebaseIndices(inner.pattern(), args.size()));
                    args.addAll(inner.args());
                } else {
                    pattern.append('{').append(args.size()).append('}');
                    args.add(e.expression());
                }
            }
        }
        return new Translatable(pattern.toString(), args);
    }

    // -- String.format --------------------------------------------------------------

    private static boolean isStringFormat(MethodCallExpr c) {
        return "format".equals(c.getNameAsString())
            && c.getScope().filter(s -> s instanceof NameExpr n
                  && "String".equals(n.getNameAsString())).isPresent();
    }

    private Translatable fromStringFormat(MethodCallExpr call) {
        List<Expression> args = call.getArguments();
        if (args.isEmpty()) return null;

        // String.format(Locale, fmt, args...) — skip leading Locale
        int fmtIdx = (args.get(0) instanceof StringLiteralExpr) ? 0 : 1;
        if (fmtIdx >= args.size()) return null;
        if (!(args.get(fmtIdx) instanceof StringLiteralExpr litFmt)) return null;

        String fmt = litFmt.asString();
        List<Expression> values = new ArrayList<>(args.subList(fmtIdx + 1, args.size()));

        StringBuilder pattern = new StringBuilder();
        Matcher m = PRINTF_SPEC.matcher(fmt);
        int auto = 0;
        int last = 0;

        while (m.find()) {
            pattern.append(escapeMF(fmt.substring(last, m.start())));
            last = m.end();
            char conv = m.group(2).charAt(0);

            if (conv == '%') { pattern.append('%'); continue; }
            if (conv == 'n') { pattern.append('\n'); continue; }

            int idx = (m.group(1) != null)
                ? Integer.parseInt(m.group(1)) - 1
                : auto++;
            pattern.append('{').append(idx);
            switch (conv) {
                case 'd', 'o', 'x', 'X' -> pattern.append(",number,integer");
                case 'f', 'e', 'g'      -> pattern.append(",number");
                case 't', 'T'           -> pattern.append(",date");
                default                 -> { /* %s, %S, %b, %c, %h: plain {N} */ }
            }
            pattern.append('}');
        }
        pattern.append(escapeMF(fmt.substring(last)));

        return new Translatable(pattern.toString(), values);
    }

    // -- MessageFormat.format -------------------------------------------------------

    private static boolean isMessageFormat(MethodCallExpr c) {
        return "format".equals(c.getNameAsString())
            && c.getScope().filter(s -> s instanceof NameExpr n
                  && "MessageFormat".equals(n.getNameAsString())).isPresent();
    }

    private Translatable fromMessageFormat(MethodCallExpr call) {
        List<Expression> args = call.getArguments();
        if (args.isEmpty()) return null;
        if (!(args.get(0) instanceof StringLiteralExpr litFmt)) return null;

        return new Translatable(
            litFmt.asString(),
            new ArrayList<>(args.subList(1, args.size())));
    }

    // -- StringBuilder / StringBuffer ----------------------------------------------

    private static boolean isStringBuilderToString(MethodCallExpr c) {
        return "toString".equals(c.getNameAsString())
            && c.getArguments().isEmpty()
            && c.getScope().isPresent();
    }

    private Translatable fromStringBuilder(MethodCallExpr toStr) {
        Expression scope = toStr.getScope().orElseThrow();
        if (scope instanceof MethodCallExpr || scope instanceof ObjectCreationExpr) {
            Translatable inline = fromStringBuilderInline(toStr);
            if (inline != null) return inline;
        }
        if (scope instanceof NameExpr) {
            return fromStringBuilderVariable(toStr);
        }
        return null;
    }

    /** Inline: new StringBuilder().append(a).append(b).toString() */
    private Translatable fromStringBuilderInline(MethodCallExpr toStr) {
        Deque<Expression> appendArgs = new ArrayDeque<>();
        Expression current = toStr.getScope().orElseThrow();

        while (current instanceof MethodCallExpr call
               && "append".equals(call.getNameAsString())
               && call.getArguments().size() == 1
               && call.getScope().isPresent()) {
            appendArgs.push(call.getArgument(0));
            current = call.getScope().get();
        }

        if (!(current instanceof ObjectCreationExpr ctor)) return null;
        if (!isBuilderType(ctor.getTypeAsString())) return null;

        List<Part> parts = new ArrayList<>();
        if (!ctor.getArguments().isEmpty()) flattenInto(ctor.getArgument(0), parts);
        for (Expression a : appendArgs) flattenInto(a, parts);
        return build(parts);
    }

    /** Multi-statement: declared StringBuilder used linearly across statements. */
    private Translatable fromStringBuilderVariable(MethodCallExpr toStr) {
        if (!(toStr.getScope().orElseThrow() instanceof NameExpr nameRef)) return null;
        String varName = nameRef.getNameAsString();

        MethodDeclaration method = toStr.findAncestor(MethodDeclaration.class).orElse(null);
        if (method == null) return null;

        VariableDeclarator decl = method.findAll(VariableDeclarator.class).stream()
            .filter(v -> v.getNameAsString().equals(varName))
            .filter(v -> isBuilderType(v.getTypeAsString()))
            .findFirst().orElse(null);
        if (decl == null) return null;
        if (!(decl.getInitializer().orElse(null) instanceof ObjectCreationExpr ctor)) return null;
        if (!isBuilderType(ctor.getTypeAsString())) return null;

        BlockStmt usageBlock = toStr.findAncestor(BlockStmt.class).orElse(null);
        if (usageBlock == null) return null;

        List<NameExpr> refs = method.findAll(NameExpr.class).stream()
            .filter(n -> n.getNameAsString().equals(varName))
            .toList();

        record Anchor(Position pos, Expression arg) {}
        List<Anchor> appendArgs = new ArrayList<>();
        Set<Node> cleanup = new HashSet<>();

        for (NameExpr ref : refs) {
            if (ref == nameRef) continue;
            // Same block as the toString() call — refuse cross-block extraction
            if (ref.findAncestor(BlockStmt.class).orElse(null) != usageBlock) return null;

            // Immediate parent must be MethodCallExpr where ref is the scope
            if (!(ref.getParentNode().orElse(null) instanceof MethodCallExpr parent)) return null;
            if (parent.getScope().filter(s -> s == ref).isEmpty()) return null;

            String name = parent.getNameAsString();
            if ("toString".equals(name)) return null;        // consumed elsewhere
            if (!"append".equals(name) || parent.getArguments().size() != 1) return null;

            // Walk forward through any chain: sb.append(a).append(b).append(c)
            MethodCallExpr cursor = parent;
            while (cursor != null
                   && "append".equals(cursor.getNameAsString())
                   && cursor.getArguments().size() == 1) {
                Position p = cursor.getArgument(0).getRange()
                    .map(r -> r.begin).orElse(new Position(0, 0));
                appendArgs.add(new Anchor(p, cursor.getArgument(0)));

                final MethodCallExpr cur = cursor;     // snapshot for lambda
                Node above = cur.getParentNode().orElse(null);
                cursor = (above instanceof MethodCallExpr next
                          && next.getScope().filter(s -> s == cur).isPresent())
                         ? next : null;
            }

            // Mark the enclosing statement for deletion
            ref.findAncestor(ExpressionStmt.class).ifPresent(cleanup::add);
        }

        appendArgs.sort(Comparator.comparing(Anchor::pos));

        List<Part> parts = new ArrayList<>();
        if (!ctor.getArguments().isEmpty()) flattenInto(ctor.getArgument(0), parts);
        for (Anchor a : appendArgs) flattenInto(a.arg(), parts);

        Translatable t = build(parts);
        if (t == null) return null;

        // Queue the declaration for removal:
        //  - if it's the sole declarator in its VariableDeclarationExpr, remove the whole statement
        //  - otherwise just detach this one declarator from the list
        decl.findAncestor(VariableDeclarationExpr.class).ifPresent(varDecl -> {
            if (varDecl.getVariables().size() == 1) {
                varDecl.findAncestor(ExpressionStmt.class).ifPresent(cleanup::add);
            } else {
                cleanup.add(decl);
            }
        });

        return new Translatable(t.pattern(), t.args(), List.copyOf(cleanup));
    }

    private static boolean isBuilderType(String typeName) {
        return "StringBuilder".equals(typeName) || "StringBuffer".equals(typeName);
    }

    // -- Pattern utilities ----------------------------------------------------------

    private static String rebaseIndices(String pattern, int offset) {
        if (offset == 0) return pattern;
        Matcher m = MF_PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            m.appendReplacement(out,
                Matcher.quoteReplacement("{" + (idx + offset) + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Escape MessageFormat metacharacters: ' becomes '', { and } get quoted. */
    private static String escapeMF(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'')                  out.append("''");
            else if (c == '{' || c == '}') { out.append('\'').append(c).append('\''); }
            else                            out.append(c);
        }
        return out.toString();
    }

    // -- Heuristics: is this pattern user-facing? ----------------------------------

    private static final Pattern PROPERTY_KEY = Pattern.compile("^[a-z][a-z0-9._-]*$");
    private static final Pattern CONSTANT     = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Pattern SQL_KEYWORD  = Pattern.compile(
        "(?i)\\b(SELECT|FROM|WHERE|INSERT INTO|UPDATE|DELETE FROM|CREATE TABLE|DROP TABLE|ALTER TABLE|JOIN)\\b");
    private static final Pattern HAS_LETTERS  = Pattern.compile(".*[A-Za-z]{2,}.*");

    private boolean shouldExtract(String s) {
        if (s == null) return false;
        String t = s.strip();
        if (t.isEmpty() || t.length() < 2) return false;
        if (PROPERTY_KEY.matcher(t).matches()) return false;
        if (CONSTANT.matcher(t).matches()) return false;
        if (SQL_KEYWORD.matcher(t).find()) return false;
        if (t.startsWith("/") || t.startsWith("http://") || t.startsWith("https://")
            || t.startsWith("file://") || t.startsWith("jdbc:")) return false;
        return HAS_LETTERS.matcher(t).matches();
    }

    // -- Key naming ----------------------------------------------------------------

    /**
     * @param className   the enclosing class (used to namespace the slug-based key)
     * @param pattern     the message pattern (used for both dedup lookup and slug)
     * @param preferred   optional explicit key base — when present, skip slug generation
     *                    and use this directly (still subject to dedup-suffix logic)
     */
    private String makeKey(String className, String pattern, String preferred) {
        // Reuse existing key for the same pattern (deduplication)
        String existing = reverseBundle.get(pattern);
        if (existing != null) return existing;

        String base;
        if (preferred != null && !preferred.isEmpty()) {
            base = preferred;
        } else {
            String slug = pattern.toLowerCase(Locale.ROOT)
                .replaceAll("\\{[^}]*\\}", " ")            // strip placeholders
                .replaceAll("[^a-z0-9 ]+", " ")
                .strip();
            String[] words = slug.split("\\s+");
            String slugStr = words.length == 0 || words[0].isEmpty()
                ? "msg"
                : String.join(".", Arrays.asList(words).subList(0, Math.min(4, words.length)));
            if (slugStr.length() > 60) slugStr = slugStr.substring(0, 60);
            base = (className.isEmpty() ? "" : className + ".") + slugStr;
        }

        String key = base;
        int suffix = 2;
        while (bundle.containsKey(key)) {
            key = base + "." + suffix++;
        }
        return key;
    }
}
