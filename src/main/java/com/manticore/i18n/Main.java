package com.manticore.i18n;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Entry point.
 *
 * Usage:
 *   java -jar i18n-extractor.jar <project-folder> [options]
 *
 * The project folder must contain src/main/java (sources) and will receive the
 * generated bundle in src/main/resources. Override either with --src / --res
 * for non-standard layouts.
 *
 * Options:
 *   --bundle <name>          base name for the bundle (default: messages)
 *   --locale <code>          locale suffix for source bundle (default: en)
 *   --helper-package <pkg>   package of the I18n helper class (default: com.manticore.i18n)
 *   --src <relative-path>    source root relative to project (default: src/main/java)
 *   --res <relative-path>    resources root relative to project (default: src/main/resources)
 *   --dry-run                analyse only, do not write files
 *   --emit-helper            write the I18n.java helper into the project
 *   --check                  CI mode: dry-run + non-zero exit if any issues found
 */
public final class Main {

    private static final String DEFAULT_BUNDLE = "messages";
    private static final String DEFAULT_LOCALE = "en";
    private static final String DEFAULT_HELPER_PKG = "com.manticore.i18n";
    private static final String DEFAULT_SRC = "src/main/java";
    private static final String DEFAULT_RES = "src/main/resources";

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        String folder = args[0];
        String bundleName = DEFAULT_BUNDLE;
        String locale = DEFAULT_LOCALE;
        String helperPackage = DEFAULT_HELPER_PKG;
        String srcRel = DEFAULT_SRC;
        String resRel = DEFAULT_RES;
        boolean dryRun = false;
        boolean emitHelper = false;
        boolean check = false;
        Map<String, int[]> uiConstructors = new HashMap<>();
        Set<String> constraintMethods = new HashSet<>();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--bundle"            -> bundleName    = args[++i];
                case "--locale"            -> locale        = args[++i];
                case "--helper-package"    -> helperPackage = args[++i];
                case "--src"               -> srcRel        = args[++i];
                case "--res"               -> resRel        = args[++i];
                case "--dry-run"           -> dryRun        = true;
                case "--emit-helper"       -> emitHelper    = true;
                case "--check"             -> { check = true; dryRun = true; }
                case "--ui-constructor"    -> parseUiConstructor(args[++i], uiConstructors);
                case "--constraint-method" -> constraintMethods.add(args[++i]);
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        Path root = Paths.get(folder).toAbsolutePath().normalize();
        Path javaSrc  = root.resolve(srcRel).normalize();
        Path resSrc   = root.resolve(resRel).normalize();
        Path bundlePath = resSrc.resolve(bundleName + "_" + locale + ".properties");

        if (!Files.isDirectory(javaSrc)) {
            System.err.println("Source folder not found: " + javaSrc);
            System.err.println("Expected Maven/Gradle layout: <folder>/src/main/java and <folder>/src/main/resources");
            System.err.println("Override with --src <path> and --res <path> if your layout differs.");
            System.exit(1);
        }

        System.out.println("[i18n] root          " + root);
        System.out.println("[i18n] sources       " + javaSrc);
        System.out.println("[i18n] bundle        " + bundlePath);
        System.out.println("[i18n] helper class  " + helperPackage + ".I18n");
        System.out.println("[i18n] dry-run       " + dryRun);
        System.out.println();

        ParserConfiguration cfg = new ParserConfiguration()
                                          .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(cfg);

        // Bundle accumulator: insertion-ordered for stable output
        Map<String, String> bundle = new LinkedHashMap<>();

        // Load any existing bundle so we don't clobber prior translations
        if (Files.isRegularFile(bundlePath)) {
            BundleIO.load(bundlePath, bundle);
            System.out.println("[i18n] loaded " + bundle.size() + " existing entries");
        }

        Extractor extractor = new Extractor(bundle, helperPackage, uiConstructors, constraintMethods);

        int filesScanned = 0, filesModified = 0, totalExtractions = 0;
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(javaSrc)) {
            sources = stream
                              .filter(p -> p.toString().endsWith(".java"))
                              .sorted()
                              .toList();
        }

        for (Path javaFile : sources) {
            filesScanned++;
            ParseResult<CompilationUnit> result = parser.parse(javaFile);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                System.err.println("[skip] parse error: " + javaFile);
                result.getProblems().forEach(p -> System.err.println("       " + p.getMessage()));
                continue;
            }

            CompilationUnit cu = result.getResult().get();
            LexicalPreservingPrinter.setup(cu);

            int beforeSize = bundle.size();
            int beforeMods = extractor.totalReplacements();
            extractor.processFile(javaFile, cu);
            int extractionsHere = extractor.totalReplacements() - beforeMods;

            if (extractionsHere > 0) {
                totalExtractions += extractionsHere;
                filesModified++;
                String newSrc = LexicalPreservingPrinter.print(cu);
                if (!dryRun) {
                    Files.writeString(javaFile, newSrc, StandardCharsets.UTF_8);
                }
                System.out.printf("[%s] %s (+%d, bundle=%d)%n",
                                  dryRun ? "DRY" : "MOD",
                                  root.relativize(javaFile),
                                  extractionsHere,
                                  bundle.size() - beforeSize);
            }
        }

        System.out.println();
        System.out.printf("[i18n] scanned   %d file(s)%n", filesScanned);
        System.out.printf("[i18n] modified  %d file(s)%n", filesModified);
        System.out.printf("[i18n] extracted %d call site(s)%n", totalExtractions);
        System.out.printf("[i18n] bundle    %d entr(y/ies)%n", bundle.size());

        if (emitHelper && !dryRun) {
            Path helperPath = javaSrc.resolve(helperPackage.replace('.', '/')).resolve("I18n.java");
            Files.createDirectories(helperPath.getParent());
            if (!Files.exists(helperPath)) {
                Files.writeString(helperPath, helperSource(helperPackage, bundleName), StandardCharsets.UTF_8);
                System.out.println("[i18n] wrote     " + helperPath);
            } else {
                System.out.println("[i18n] helper    already exists at " + helperPath + " (not overwritten)");
            }
        }

        // -- Post-extraction maintenance reports -----------------------------------
        // Always run; useful both in extract mode (to spot drift) and check mode.

        Map<String, TrRef> refs = scanTrReferences(javaSrc, parser);
        Set<String> referencedKeys = refs.keySet();
        Set<String> orphans = new TreeSet<>(bundle.keySet());
        orphans.removeAll(referencedKeys);

        // Heuristic: dynamic keys (computed at runtime) can't be statically tracked.
        // Devs annotate them with: //$NLS-KEEP$ key1,key2  on the line above.
        // Read keep-list from those comments.
        Set<String> kept = scanKeepAnnotations(javaSrc);
        orphans.removeAll(kept);

        if (!orphans.isEmpty()) {
            System.out.println();
            System.out.printf("[i18n] %d orphan key(s) — present in bundle, no I18n.tr() reference in source:%n",
                              orphans.size());
            for (String k : orphans) {
                System.out.println("       - " + k);
            }
            System.out.println("       (annotate dynamic keys with //$NLS-KEEP$ key1,key2 to silence)");
        }

        // Unresolved-key report + auto-fill — the mirror image of the orphan report.
        // These are keys referenced by I18n.tr("literal", ...) that have no entry in
        // the bundle yet (typically hand-written ahead of extraction). Outside of
        // dry-run we fill each with a best-effort English value: the humanised key
        // plus one {0..n-1} placeholder per call argument. Values are deliberately
        // rough — review them in the diff and refine in the bundle.
        Set<String> unresolved = new TreeSet<>(refs.keySet());
        unresolved.removeAll(bundle.keySet());
        unresolved.removeAll(kept);          // honour //$NLS-KEEP$ for dynamic keys

        int filled = 0;
        if (!unresolved.isEmpty()) {
            System.out.println();
            System.out.printf("[i18n] %d unresolved key(s) — referenced by I18n.tr(...), absent from the bundle:%n",
                              unresolved.size());
            for (String k : unresolved) {
                TrRef ref = refs.get(k);
                String value = Extractor.suggestValue(k, ref.argCount());
                if (!dryRun) {
                    bundle.put(k, value);
                    filled++;
                }
                System.out.printf("       %s %s = %s   (%s:%d)%n",
                                  dryRun ? "?" : "+", k, value,
                                  root.relativize(ref.file()), ref.line());
            }
            System.out.println(dryRun
                               ? "       (run without --dry-run to add these to the bundle)"
                               : "       (best-effort values — review the diff and refine as needed)");
        }

        // Translation-gap report: keys present in source bundle but missing from
        // sibling locale bundles. Iterates messages_*.properties next to the source.
        Map<Path, Set<String>> missingByLocale = scanTranslationGaps(resSrc, bundleName, locale, bundle);
        for (var entry : missingByLocale.entrySet()) {
            Path other = entry.getKey();
            Set<String> missing = entry.getValue();
            if (missing.isEmpty()) continue;
            System.out.println();
            System.out.printf("[i18n] %s missing %d translation(s):%n",
                              other.getFileName(), missing.size());
            int shown = 0;
            for (String k : missing) {
                if (shown++ >= 20) {
                    System.out.printf("       ... and %d more%n", missing.size() - 20);
                    break;
                }
                System.out.println("       - " + k + " = " + truncate(bundle.get(k), 60));
            }
        }

        // Single bundle write — covers both extraction additions and unresolved fills.
        if (!dryRun && (totalExtractions > 0 || filled > 0)) {
            BundleIO.write(bundlePath, bundle);
            System.out.println();
            System.out.println("[i18n] wrote     " + bundlePath + " (" + bundle.size() + " entries)");
        }

        if (check) {
            boolean anyIssues = totalExtractions > 0
                                        || !unresolved.isEmpty()
                                        || !orphans.isEmpty()
                                        || missingByLocale.values().stream().anyMatch(s -> !s.isEmpty());
            if (anyIssues) {
                System.out.println();
                System.out.println("[i18n] CHECK FAILED — see above for issues");
                System.exit(1);
            }
            System.out.println();
            System.out.println("[i18n] CHECK PASSED");
        }
    }

    /**
     * A static reference to a key via {@code I18n.tr("key", arg, ...)}: where the
     * first such call lives (for reporting) and the call's argument count (for
     * placeholder generation). {@code argCount} is the MAX seen across all call
     * sites, so a suggested value never has fewer placeholders than some call site
     * supplies its arguments to.
     */
    record TrRef(Path file, int line, int argCount) {}

    /**
     * Find all {@code I18n.tr("literal", ...)} calls and index them by key. Only
     * string-literal first arguments are recorded — computed keys can't be matched
     * against the bundle statically (annotate those with {@code //$NLS-KEEP$}).
     */
    private static Map<String, TrRef> scanTrReferences(Path javaSrc, JavaParser parser) throws IOException {
        Map<String, TrRef> refs = new HashMap<>();
        try (Stream<Path> stream = Files.walk(javaSrc)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path file : files) {
                ParseResult<CompilationUnit> r = parser.parse(file);
                if (!r.isSuccessful() || r.getResult().isEmpty()) continue;
                CompilationUnit cu = r.getResult().get();
                for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                    if (!"tr".equals(call.getNameAsString())) continue;
                    if (!(call.getScope().orElse(null) instanceof NameExpr scope)) continue;
                    if (!"I18n".equals(scope.getNameAsString())) continue;
                    if (call.getArguments().isEmpty()) continue;
                    if (!(call.getArgument(0) instanceof StringLiteralExpr lit)) continue;

                    String key = lit.asString();
                    int argCount = call.getArguments().size() - 1;   // first arg is the key
                    int line = call.getRange().map(rg -> rg.begin.line).orElse(0);

                    TrRef prev = refs.get(key);
                    if (prev == null) {
                        refs.put(key, new TrRef(file, line, argCount));
                    } else if (argCount > prev.argCount()) {
                        refs.put(key, new TrRef(prev.file(), prev.line(), argCount));
                    }
                }
            }
        }
        return refs;
    }

    /** Parse //$NLS-KEEP$ key1,key2 comments and collect the listed keys. */
    private static final java.util.regex.Pattern NLS_KEEP =
            java.util.regex.Pattern.compile("//\\s*\\$NLS-KEEP\\$\\s*([\\w.,\\s-]+)");

    private static Set<String> scanKeepAnnotations(Path javaSrc) throws IOException {
        Set<String> kept = new HashSet<>();
        try (Stream<Path> stream = Files.walk(javaSrc)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : files) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                java.util.regex.Matcher m = NLS_KEEP.matcher(src);
                while (m.find()) {
                    for (String k : m.group(1).split("[,\\s]+")) {
                        if (!k.isBlank()) kept.add(k.trim());
                    }
                }
            }
        }
        return kept;
    }

    /** For each sibling messages_*.properties, return keys present in source bundle but missing from it. */
    private static Map<Path, Set<String>> scanTranslationGaps(
            Path resSrc, String bundleName, String sourceLocale, Map<String, String> sourceBundle) throws IOException {
        Map<Path, Set<String>> result = new LinkedHashMap<>();
        if (!Files.isDirectory(resSrc)) return result;
        String prefix = bundleName + "_";
        String selfName = prefix + sourceLocale + ".properties";

        try (Stream<Path> stream = Files.list(resSrc)) {
            List<Path> peers = stream
                                       .filter(p -> {
                                           String n = p.getFileName().toString();
                                           return n.startsWith(prefix) && n.endsWith(".properties") && !n.equals(selfName);
                                       })
                                       .sorted()
                                       .toList();
            for (Path peer : peers) {
                Map<String, String> other = new LinkedHashMap<>();
                BundleIO.load(peer, other);
                Set<String> missing = new TreeSet<>(sourceBundle.keySet());
                missing.removeAll(other.keySet());
                // Also count empty values as "missing"
                for (String k : new ArrayList<>(other.keySet())) {
                    if (sourceBundle.containsKey(k) && other.get(k).isBlank()) missing.add(k);
                }
                result.put(peer, missing);
            }
        }
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace("\n", "\\n").replace("\r", "");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static String helperSource(String pkg, String bundleName) {
        return """
                package %s;

                import java.text.MessageFormat;
                import java.util.Locale;
                import java.util.MissingResourceException;
                import java.util.ResourceBundle;

                /**
                 * Minimal i18n helper. Generated by i18n-extractor.
                 *
                 * Place messages_<locale>.properties on the classpath under the bundle base name.
                 */
                public final class I18n {

                    private static volatile ResourceBundle bundle =
                        ResourceBundle.getBundle("%s", Locale.getDefault());

                    private I18n() {}

                    public static String tr(String key, Object... args) {
                        try {
                            String pattern = bundle.getString(key);
                            return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
                        } catch (MissingResourceException e) {
                            return key;
                        }
                    }

                    public static void setLocale(Locale locale) {
                        bundle = ResourceBundle.getBundle("%s", locale);
                    }
                }
                """.formatted(pkg, bundleName, bundleName);
    }

    /** Parse `ClassName:idx1,idx2,...` and add to the constructor map. */
    private static void parseUiConstructor(String spec, Map<String, int[]> into) {
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) {
            System.err.println("Bad --ui-constructor spec: " + spec
                                       + " (expected: ClassName:pos1,pos2,...)");
            System.exit(1);
        }
        String className = spec.substring(0, colon).trim();
        String[] parts = spec.substring(colon + 1).split(",");
        int[] positions = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) positions[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            System.err.println("Bad --ui-constructor spec: " + spec
                                       + " (positions must be integers)");
            System.exit(1);
        }
        into.put(className, positions);
    }

    private static void printUsage() {
        System.err.println("""
            Usage: i18n-extractor <project-folder> [options]

            Default project layout (Maven/Gradle):
              <folder>/src/main/java       sources
              <folder>/src/main/resources  bundle output

            Options:
              --bundle <name>             base name for the bundle (default: messages)
              --locale <code>             locale suffix for source bundle (default: en)
              --helper-package <pkg>      package of the I18n helper class (default: com.manticore.i18n)
              --src <relative-path>       source root relative to project (default: src/main/java)
              --res <relative-path>       resources root relative to project (default: src/main/resources)
              --dry-run                   analyse only, do not write files
              --emit-helper               write the I18n.java helper into the project
              --check                     CI mode: dry-run + non-zero exit if any issues
              --ui-constructor <SPEC>     register a custom UI/text constructor pattern.
                                          SPEC format: ClassName:pos1,pos2,...
                                          Example: --ui-constructor Action:1,3
                                          (Built-in Swing: JButton, JLabel, JMenu, JMenuItem,
                                           JCheckBox, JRadioButton, JToggleButton, JFrame,
                                           JDialog, AbstractAction, ProgressMonitor, etc.)
                                          (Built-in exceptions: Exception, RuntimeException,
                                           IllegalArgumentException, IllegalStateException,
                                           UnsupportedOperationException, IOException,
                                           SQLException — message at pos 0)
              --constraint-method <name>  register a method whose String arguments are
                                          constraint strings of the form
                                          "key1=value1,key2=value2,..." — for label/tooltip
                                          values, adds bundle entries WITHOUT rewriting the
                                          call site. The runtime helper (e.g. GridBagPane)
                                          looks up translations via I18n.localize(text).
                                          Example: --constraint-method add
            """);
    }
}