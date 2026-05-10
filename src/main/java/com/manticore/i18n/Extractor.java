package com.manticore.i18n;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
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

    /**
     * Parsed result of a {@code // @I18N(...)} directive found in a comment.
     *
     * <p>Two syntaxes are supported:</p>
     * <pre>
     *   // @I18N(1, 3)               — numeric: extract positions 1 and 3
     *   // @I18N("KEY, TEXT, SKIP, TEXT")  — role-based:
     *       KEY  = identifier column; its value is used as the bundle-key prefix
     *       TEXT = user-facing text; extract and translate
     *       SKIP = not user-facing; ignore
     * </pre>
     *
     * @param textPositions  column indices to extract as translatable text
     * @param keyPosition    column index holding the identifier key (-1 = none)
     */
    record I18nDirective(int[] textPositions, int keyPosition) {}

    /**
     * Matches {@code @I18N(...)} inside any comment text.
     * Group 1 captures the content inside the parentheses.
     */
    private static final Pattern I18N_COMMENT_PATTERN =
        Pattern.compile("@I18N\\s*\\(([^)]+)\\)");

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
        Map.entry("ProgressMonitor",        new int[]{1, 2}),     // (parent, message, note, ...)
        // Exception types — message at position 0. The + concatenation handler in
        // extractTranslatable picks up multi-part patterns like
        //   throw new Exception("User " + uid + " not allowed")
        // and produces a single MessageFormat pattern with one argument.
        // Deliberately excluded: NullPointerException, ClassCastException,
        // NumberFormatException, ConcurrentModificationException — these are
        // typically programmatic, not user-facing prose. Add via --ui-constructor
        // for project-specific exception classes (BusinessException, etc.).
        Map.entry("Exception",                     new int[]{0}),
        Map.entry("RuntimeException",              new int[]{0}),
        Map.entry("IllegalArgumentException",      new int[]{0}),
        Map.entry("IllegalStateException",         new int[]{0}),
        Map.entry("UnsupportedOperationException", new int[]{0}),
        Map.entry("IOException",                   new int[]{0}),
        Map.entry("SQLException",                  new int[]{0})
    );

    private final Map<String, String> bundle;
    private final Map<String, String> reverseBundle = new HashMap<>();
    private final String helperPackage;
    private final Map<String, int[]> uiConstructors;
    private final Set<String> constraintMethods;
    private int totalReplacements = 0;
    private int totalConstraintExtractions = 0;

    /**
     * Constraint-string keys whose values are user-facing text and should be
     * extracted into the bundle. Matched case-insensitively against the LHS of
     * each {@code key=value} pair inside a constraint string.
     */
    private static final Set<String> TRANSLATABLE_CONSTRAINT_KEYS = Set.of(
        "label", "tooltip", "settooltiptext"
    );

    // Per-file state, reset on each processFile() call
    private String currentClass = "";
    private Set<Node> nodesToRemove = new HashSet<>();

    public Extractor(Map<String, String> bundle, String helperPackage) {
        this(bundle, helperPackage, Map.of(), Set.of());
    }

    public Extractor(Map<String, String> bundle, String helperPackage,
                     Map<String, int[]> additionalUiConstructors) {
        this(bundle, helperPackage, additionalUiConstructors, Set.of());
    }

    public Extractor(Map<String, String> bundle, String helperPackage,
                     Map<String, int[]> additionalUiConstructors,
                     Set<String> constraintMethods) {
        this.bundle = bundle;
        this.helperPackage = helperPackage;
        Map<String, int[]> merged = new HashMap<>(BUILTIN_UI_CTORS);
        merged.putAll(additionalUiConstructors);
        this.uiConstructors = Map.copyOf(merged);
        this.constraintMethods = Set.copyOf(constraintMethods);
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
                processArgument(arg, (MethodCallExpr) null);
            }
        }

        // Pass 4: 2D array fields. Two modes (Mode A and Mode B).
        //
        //   Mode A — explicit @I18N directive on the field:
        //      Use the directive positions directly. Works for ANY 2D array type,
        //      static or instance, no UI-constructor registration needed.
        //
        //   Mode B — no directive (conservative legacy behaviour):
        //      Only `static Object[][]` fields whose rows contain a registered
        //      UI-constructor array; extracts row[0]. Prevents data tables from
        //      being over-extracted when no explicit annotation is present.
        for (FieldDeclaration field : cu.findAll(FieldDeclaration.class)) {
            I18nDirective directive = parseI18nDirective(field);

            // Mode B requires static; Mode A doesn't (instance fields are fine
            // when the developer has explicitly annotated them).
            if (directive == null && !field.isStatic()) continue;

            for (VariableDeclarator var : field.getVariables()) {
                String typeName = var.getTypeAsString();
                boolean is2dArray = typeName.contains("[][]");
                if (directive == null && !"Object[][]".equals(typeName)) continue;
                if (directive != null && !is2dArray) continue;

                ArrayInitializerExpr outer = unwrapInitializer(var.getInitializer().orElse(null));
                if (outer == null) continue;

                for (Expression rowExpr : outer.getValues()) {
                    if (!(rowExpr instanceof ArrayInitializerExpr row)) continue;
                    if (row.getValues().isEmpty()) continue;

                    if (directive != null) {
                        extractRowByDirective(row, directive);
                    } else {
                        Expression first = row.getValues().get(0);
                        if (!(first instanceof StringLiteralExpr)) continue;
                        if (!rowContainsRegisteredUIArray(row)) continue;
                        if (first.getParentNode().isEmpty()) continue;
                        processArgument(first, (MethodCallExpr) null);
                    }
                }
            }
        }

        // Pass 5: @I18N-annotated 2D arrays inside method bodies (assignments and
        //         local-variable declarations).
        //
        // Critical detail: JavaParser's comment-attachment is quirky for line comments
        // immediately above statements deep inside method bodies — the comment may end
        // up as an "orphan" on the enclosing block rather than as the statement's own
        // .getComment(). We sidestep that entirely by walking ALL comments in the file,
        // matching @I18N ones, and then finding the AST element that starts on the
        // line directly following each comment. This is robust across JavaParser
        // versions and forks.
        Set<Node> alreadyProcessed = new HashSet<>();
        for (Comment comment : cu.getAllContainedComments()) {
            I18nDirective directive = parseComment(comment);
            if (directive == null) continue;

            Optional<com.github.javaparser.Range> cr = comment.getRange();
            if (cr.isEmpty()) continue;
            int commentEndLine = cr.get().end.line;

            Node target = findFollowingTarget(cu, commentEndLine);
            if (target == null) continue;
            if (!alreadyProcessed.add(target)) continue;     // Pass 4 may have hit it

            applyDirectiveToNode(target, directive);
        }

        // Pass 6: Constraint-method strings — bundle-only (no AST rewrite).
        //
        // For methods registered via --constraint-method (e.g., GridBagPane.add),
        // walks every String-literal argument and parses it as a comma-separated
        // list of "key=value" pairs (matching GridBagPane's f.split(",+") syntax).
        // For each label/tooltip/setToolTipText key found, extracts the value as a
        // bundle entry under a slug-derived key. The call site is NOT modified —
        // the receiving helper is expected to do runtime lookup via the same slug.
        //
        // GridBagPane prefix markers (!, *, ?, +) on label values are stripped
        // before slugging so "!Required Field" and "Required Field" produce the
        // same bundle key.
        if (!constraintMethods.isEmpty()) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (call.getParentNode().isEmpty()) continue;
                if (!constraintMethods.contains(call.getNameAsString())) continue;
                for (Expression arg : call.getArguments()) {
                    if (arg instanceof StringLiteralExpr lit) {
                        extractConstraintString(lit.asString());
                    }
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

    /**
     * Extracts translatable text from a single array row using an {@link I18nDirective}.
     *
     * <p>For each TEXT position in the directive, the cell at that position is
     * processed.  If the directive also specifies a KEY position and the key cell
     * is a plain string literal, that literal's value is used to build a more
     * descriptive preferred bundle key (instead of the default slug-from-text):</p>
     * <ul>
     *   <li>First TEXT cell in the row → preferred key = {@code ClassName.keyValue}</li>
     *   <li>Subsequent TEXT cells      → preferred key = {@code ClassName.keyValue.slug(text)}</li>
     * </ul>
     */
    private void extractRowByDirective(ArrayInitializerExpr row, I18nDirective directive) {
        NodeList<Expression> cells = row.getValues();

        // Resolve KEY-column value (may be null)
        String keyValue = null;
        if (directive.keyPosition() >= 0 && directive.keyPosition() < cells.size()) {
            Expression keyCel = cells.get(directive.keyPosition());
            if (keyCel instanceof StringLiteralExpr kl) keyValue = kl.asString();
        }

        boolean firstText = true;
        for (int pos : directive.textPositions()) {
            if (pos < 0 || pos >= cells.size()) continue;
            Expression cell = cells.get(pos);
            if (cell.getParentNode().isEmpty()) continue;     // already replaced
            String preferred = buildI18nPreferredKey(keyValue, cell, firstText);
            processArgument(cell, preferred);
            firstText = false;
        }
    }

    /**
     * Locates the AST element whose start line is just after {@code afterLine} —
     * used by Pass 5 to associate a stray @I18N comment with whatever comes next
     * (FieldDeclaration, ExpressionStmt with a local var or an assignment).
     *
     * <p>Returns null if the next candidate is more than 3 lines away (we only
     * accept comments that are clearly adjacent to their target).</p>
     */
    private static Node findFollowingTarget(CompilationUnit cu, int afterLine) {
        Node best = null;
        int bestStartLine = Integer.MAX_VALUE;

        for (FieldDeclaration f : cu.findAll(FieldDeclaration.class)) {
            Optional<com.github.javaparser.Range> r = f.getRange();
            if (r.isEmpty()) continue;
            int start = r.get().begin.line;
            if (start > afterLine && start < bestStartLine) {
                best = f;
                bestStartLine = start;
            }
        }
        for (ExpressionStmt s : cu.findAll(ExpressionStmt.class)) {
            Optional<com.github.javaparser.Range> r = s.getRange();
            if (r.isEmpty()) continue;
            int start = r.get().begin.line;
            if (start > afterLine && start < bestStartLine) {
                best = s;
                bestStartLine = start;
            }
        }

        // Reasonable proximity: comment must be within 3 lines of its target.
        if (best != null && bestStartLine - afterLine > 3) return null;
        return best;
    }

    /**
     * Applies an {@link I18nDirective} to a target AST node — either a field
     * declaration or an expression statement (assignment / local variable).
     * Handles the unwrap from `new String[][] { ... }` down to the inner
     * {@link ArrayInitializerExpr} and walks each row.
     */
    private void applyDirectiveToNode(Node target, I18nDirective directive) {
        if (target instanceof FieldDeclaration field) {
            for (VariableDeclarator var : field.getVariables()) {
                if (!var.getTypeAsString().contains("[][]")) continue;
                walkRows(var.getInitializer().orElse(null), directive);
            }
            return;
        }
        if (target instanceof ExpressionStmt stmt) {
            Expression expr = stmt.getExpression();
            if (expr instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (!vd.getTypeAsString().contains("[][]")) continue;
                    walkRows(vd.getInitializer().orElse(null), directive);
                }
            } else if (expr instanceof AssignExpr ae) {
                walkRows(ae.getValue(), directive);
            }
        }
    }

    /** Common row-walk for any 2D array initializer expression. */
    private void walkRows(Expression initializer, I18nDirective directive) {
        ArrayInitializerExpr outer = unwrapInitializer(initializer);
        if (outer == null) return;
        for (Expression rowExpr : outer.getValues()) {
            if (rowExpr instanceof ArrayInitializerExpr row) {
                extractRowByDirective(row, directive);
            }
        }
    }

    // -- Pass 6 helpers: constraint-method extraction (bundle-only) -------------

    /**
     * Parses a constraint string of the form
     * {@code key1=value1,key2=value2,...} (matching the syntax of
     * {@code GridBagPane.add(c, f).split(",+")}) and adds a bundle entry for
     * each translatable key's value. Does NOT modify any AST.
     */
    private void extractConstraintString(String constraintStr) {
        // GridBagPane uses split(",+") — one or more commas as a separator.
        String[] parts = constraintStr.split(",+");
        for (String part : parts) {
            String[] kv = part.split("=+", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            if (!TRANSLATABLE_CONSTRAINT_KEYS.contains(key)) continue;

            String value = kv[1].trim();
            // Strip GridBagPane prefix markers (!, * for red/required, ? + for
            // blue/optional). The ORIGINAL marker stays in the source code; only
            // the bundle key derives from the stripped text, so "!Required" and
            // "Required" share the same translation.
            if (!value.isEmpty() && "!*?+".indexOf(value.charAt(0)) >= 0) {
                value = value.substring(1);
            }
            addBundleEntryOnly(value);
        }
    }

    /**
     * Bundle-only extraction. Adds a slug-keyed bundle entry, no AST rewrite.
     * The runtime helper that consumes the original literal (e.g.
     * {@code GridBagPane.parseFormatString} → {@code I18n.localize(value)}) is
     * expected to compute the same slug and look up the translation.
     *
     * <p>Slug collisions across distinct text values are warned to stderr but
     * not auto-resolved (no key-suffix dedup) — the runtime cannot predict which
     * suffix to look up. Resolve any reported collisions by adjusting the source
     * text.</p>
     */
    private void addBundleEntryOnly(String text) {
        if (text == null || text.isEmpty()) return;
        if (!shouldExtract(text)) return;

        String key = toSlug(text);
        if (key.isEmpty()) return;

        if (bundle.containsKey(key)) {
            String existing = bundle.get(key);
            if (!existing.equals(text)) {
                System.err.println("WARN: constraint-key slug collision for '" + key
                    + "': existing \"" + existing + "\" vs new \"" + text + "\""
                    + " — runtime will only find the first; adjust source text.");
            }
            return;
        }
        bundle.put(key, text);
        reverseBundle.put(text, key);
        totalConstraintExtractions++;
    }

    public int totalConstraintExtractions() {
        return totalConstraintExtractions;
    }

    // -- Per-argument processing (Pass 1) -------------------------------------------

    private void processArgument(Expression arg, MethodCallExpr context) {
        Translatable t = extractTranslatable(arg);
        if (t == null) return;
        apply(arg, t, null);
    }

    /** Variant used by @I18N passes where a preferred bundle-key is known. */
    private void processArgument(Expression arg, String preferredKey) {
        Translatable t = extractTranslatable(arg);
        if (t == null) return;
        apply(arg, t, preferredKey);
    }

    /**
     * Parses an {@code @I18N(...)} directive from the comment attached to {@code node}
     * (or its parent statement, which JavaParser often associates the comment with).
     *
     * @return the parsed directive, or {@code null} if no {@code @I18N} comment is found
     */
    private I18nDirective parseI18nDirective(Node node) {
        I18nDirective d = parseComment(node.getComment().orElse(null));
        if (d != null) return d;
        // ExpressionStmt / VariableDeclarationExpr — the comment may be on the parent
        Node parent = node.getParentNode().orElse(null);
        return parent == null ? null : parseComment(parent.getComment().orElse(null));
    }

    private static I18nDirective parseComment(Comment comment) {
        if (comment == null) return null;
        Matcher m = I18N_COMMENT_PATTERN.matcher(comment.getContent());
        if (!m.find()) return null;

        String raw = m.group(1).trim().replace("\"", "");
        String[] tokens = raw.split(",");

        List<Integer> textPos = new ArrayList<>();
        int keyPos = -1;

        // Numeric form: @I18N(1, 3)
        boolean allNumeric = Arrays.stream(tokens)
            .allMatch(t -> t.trim().matches("\\d+"));
        if (allNumeric) {
            for (String tok : tokens) textPos.add(Integer.parseInt(tok.trim()));
            return new I18nDirective(textPos.stream().mapToInt(Integer::intValue).toArray(), -1);
        }

        // Role form: @I18N("KEY, TEXT, SKIP, TEXT")
        for (int i = 0; i < tokens.length; i++) {
            String role = tokens[i].trim().toUpperCase(Locale.ROOT);
            if ("TEXT".equals(role))      textPos.add(i);
            else if ("KEY".equals(role))  keyPos = i;
            // SKIP and anything else: ignored
        }
        return new I18nDirective(textPos.stream().mapToInt(Integer::intValue).toArray(), keyPos);
    }

    /**
     * Builds the preferred bundle key for one TEXT cell inside an @I18N-annotated row.
     *
     * <ul>
     *   <li>If the row has a KEY column, the key is {@code className.keyValue} for
     *       the first TEXT column, and {@code className.keyValue.slug(text)} for
     *       subsequent ones (so each cell in the same row gets a distinct key).</li>
     *   <li>If no KEY column, returns {@code null} and the normal slug-from-text
     *       logic takes over.</li>
     * </ul>
     */
    private String buildI18nPreferredKey(String keyValue, Expression textExpr,
                                          boolean isFirstTextInRow) {
        if (keyValue == null || keyValue.isEmpty()) return null;
        String base = currentClass + "." + toSlug(keyValue);
        if (isFirstTextInRow) return base;
        if (textExpr instanceof StringLiteralExpr lit) {
            return base + "." + toSlug(lit.asString());
        }
        return base;
    }

    /** Slug helper — same logic as makeKey uses internally; extracted for reuse. */
    private static String toSlug(String text) {
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("\\{[^}]*}", " ")
            .replaceAll("[^a-z0-9 ]+", " ")
            .strip()
            .replaceAll("\\s+", ".")
            .replaceAll("^\\.+|\\.+$", "");
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
            String[] words = toSlug(pattern).split("\\.");
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
