# i18n-extractor

Externalises hardcoded user-facing strings from a Java/Swing codebase into a
`messages_<locale>.properties` ResourceBundle and rewrites call sites to use a
small `I18n.tr(...)` helper.

## Supported input shapes

- String literal: `setText("Hello")`
- `+` concatenation: `setText("Found " + count + " records")`
- `String.format(...)` (incl. positional `%1$s`, `%d`, `%f`, `%n`, `%%`)
- `MessageFormat.format(...)` (passthrough)
- Inline builder: `new StringBuilder().append(...).toString()`
- Multi-statement local builder used linearly across statements
- Methods returning a substantial String pattern (e.g. `getWelcomeText()`,
  `getLicenseNotice()`, `getAboutText()`) — covers helpers whose UI sink is in
  another class. Triggered when the pattern has ≥ 2 newlines or ≥ 100 chars.
- **Constructor arguments to UI types**: `new JButton("Save")`,
  `new JLabel("Username:")`, `new JMenu("File")`, etc. Built-in coverage of
  standard `javax.swing` classes:

  | Class | Position(s) |
    |-------|-------------|
  | `JButton`, `JCheckBox`, `JCheckBoxMenuItem`, `JRadioButton`, `JRadioButtonMenuItem`, `JToggleButton` | 0 |
  | `JLabel`, `JMenu`, `JMenuItem`, `JPopupMenu` | 0 |
  | `JFrame`, `JInternalFrame`, `JOptionPane`, `JToolBar` | 0 |
  | `JDialog` | 0, 1 (handles both `(String)` and `(owner, String)` overloads) |
  | `AbstractAction` | 0 |
  | `TitledBorder` | 0, 1 (handles both `(String)` and `(Border, String)` overloads) |
  | `ProgressMonitor` | 1, 2 (message + note) |

  Excluded from defaults (data-content rather than UI labels, register
  per-project if needed): `JTextField`, `JTextArea`, `JEditorPane`,
  `JPasswordField`, `JComboBox`, `JList`, `JTable`, `JFileChooser`. AWT
  classes also excluded — name collisions with user-defined classes are
  too easy.

  Custom classes registered via `--ui-constructor ClassName:pos1,pos2,...`.

- **Exception messages** — `throw new Exception("message")` and friends.
  Built-in coverage:

  | Class | Position |
    |-------|----------|
  | `Exception`, `RuntimeException` | 0 |
  | `IllegalArgumentException`, `IllegalStateException`, `UnsupportedOperationException` | 0 |
  | `IOException`, `SQLException` | 0 |

  Concatenation chains like
  `new Exception("User " + uid + " not allowed to write")` are flattened to a
  MessageFormat pattern with the dynamic parts as positional arguments — same
  treatment as `String.format(...)`.

  Deliberately excluded: `NullPointerException`, `ClassCastException`,
  `NumberFormatException`, `ConcurrentModificationException` — typically
  programmatic, not user-facing prose. Add via `--ui-constructor
  YourException:0` for project-specific exception classes.

- **Constraint-method strings** — for helper classes like `GridBagPane.add(c,
  "label=Account:,tooltip=The user account.")` that take a comma-separated
  `key=value` configuration string. Register the method name with
  `--constraint-method add` and the extractor:

    - parses the string via `split(",+")` (matching `GridBagPane`'s own parser);
    - for each `label=`, `tooltip=`, or `setToolTipText=` value, strips the
      `GridBagPane` prefix markers (`!`, `*`, `?`, `+`) and adds a bundle entry
      keyed by the value's slug;
    - **does not modify the call site** — the runtime helper looks up the
      translation via `I18n.localize(value)` (which computes the same slug) and
      falls back to the original literal on miss.

  This pairs with the `localize` / `slugify` methods on `I18n.java`. See the
  patched `GridBagPane.java` deliverable for the runtime side.
- **Static `Object[][]` menu/toolbar definitions**: rows of
  `{categoryLabel, new SomeAction[]{...}}` — the row's first String literal is
  extracted *if* the row also contains an array creation of a registered UI
  constructor type (so plain data tables don't get caught).
- **`@I18N` comment directive on any 2D array** (field or local variable):
  forces extraction by column without requiring a registered UI constructor.
  Two syntaxes:

  ```java
  // @I18N(1, 3)              <-- numeric: extract columns 1 and 3 of every row
  // @I18N("KEY, TEXT, SKIP, TEXT")  <-- role-based:
  //   KEY  = identifier column; its value becomes the bundle-key prefix
  //   TEXT = user-facing text; extract and translate
  //   SKIP = ignored
  ```

  Example — extracting button labels and tooltips with semantic keys:

  ```java
  // @I18N("KEY,TEXT,TEXT")
  buttonsDef = new String[][] {
      {"etlButton",    "ETL",            "Manage ETL Files"},
      {"reportButton", "Reports",        "Build Reports"},
      {"adminButton",  "Administration", "Configuration and Administration"}
  };
  ```

  Yields bundle keys `<ClassName>.etlButton = ETL` for the first TEXT cell of
  each row (using the KEY value verbatim) and `<ClassName>.etlButton.manage.etl.files
  = Manage ETL Files` for subsequent TEXT cells (KEY value + slug of the text).
  Without a KEY column, falls back to the normal slug-from-text key generation.

  For the menu-array pattern with category labels and inner UI constructors,
  combine `@I18N(0)` (or `@I18N("TEXT")`) on the field — to extract category
  strings — with `--ui-constructor Action:1,3` for the inner Action constructor
  arguments. The two mechanisms are complementary: one for the outer array, one
  for the nested constructors.
- Arbitrary nesting of all the above

## Project layout expected

The tool walks `<folder>/src/main/java` for `.java` sources and writes the
bundle to `<folder>/src/main/resources/messages_<locale>.properties` — the
standard Maven/Gradle layout. So your target project should look like:

    IFRSBox/
      src/
        main/
          java/
            com/manticore/ifrsbox/MyPanel.java
            ...
          resources/

For non-standard layouts, override with `--src` and `--res`:

    gradle run --args="myproject --src src/swing --res src/i18n"

## Build

    gradle build

Java 21 toolchain. JavaParser 3.26.x.

## Run

Two invocation styles:

    # explicit args
    gradle run --args="/home/are/Documents/src/VBox/IFRSBox"
    gradle run --args="/home/are/Documents/src/VBox/IFRSBox --dry-run --locale en --bundle messages"

    # property style
    gradle -Pfolder=/home/are/Documents/src/VBox/IFRSBox run
    gradle -Pfolder=/home/are/Documents/src/VBox/IFRSBox -PdryRun -Plocale=en run

### Options

| Option              | Property            | Default                   | Description                              |
|---------------------|---------------------|---------------------------|------------------------------------------|
| `--bundle <name>`   | `-Pbundle=`         | `messages`                | Bundle base name                         |
| `--locale <code>`   | `-Plocale=`         | `en`                      | Locale suffix                            |
| `--helper-package`  | `-PhelperPackage=`  | `com.manticore.i18n`      | Package of the `I18n.tr` helper          |
| `--src <path>`      | `-Psrc=`            | `src/main/java`           | Source root relative to project          |
| `--res <path>`      | `-Pres=`            | `src/main/resources`      | Resources root relative to project       |
| `--dry-run`         | `-PdryRun`          | off                       | Analyse only, no file writes             |
| `--emit-helper`     | `-PemitHelper`      | off                       | Write the `I18n.java` helper into project|
| `--check`           | `-Pcheck`           | off                       | CI mode: dry-run + non-zero exit on issues |

## Recommended workflow

1. **Commit a clean baseline** — the tool rewrites source files in place; you
   want a clean diff to review.
2. **Dry-run first** to see scope and key naming:

       gradle run --args="/path/to/project --dry-run --emit-helper"

3. **Run for real** on one module/package at a time. Constrain the `src/java`
   path to scope the run if needed.
4. **Review the diff.** Common things to look for:
    - log messages, SQL strings, or property keys that got extracted
      (heuristics catch most but not all)
    - concatenations that produced ugly keys — rename in both source and
      bundle before translation
    - multi-statement builder cleanup that left a comment orphaned
5. **Add the I18n helper** if not using `--emit-helper`. Place
   `I18n.java` in the configured helper package on the classpath.
6. **Translate** the bundle. For Bahasa Indonesia, copy
   `messages_en.properties` → `messages_id.properties` and translate values
   only (never keys).

## Method-return extraction

When a method returns a `String` and the pattern is "substantial" (≥ 2 newlines
OR ≥ 100 chars), the extractor will pull the whole returned expression into the
bundle. The key is `<ClassName>.<methodName>` directly, e.g.
`AccountPanel.getWelcomeText`, ignoring the slug heuristic.

Refusal cases (left untouched):

- Methods named `toString`, `hashCode`, `equals`, `clone`, `getClass`,
  `getName`, `getId`, `getKey`, `getCode`
- Returns inside nested lambdas (return target isn't the enclosing method)
- Patterns under the substantial-text threshold (most exception messages,
  toString helpers, getters fall into this bucket)
- Any pattern that fails `shouldExtract` (SQL, URLs, property keys, etc.)

If a method falsely passes (e.g. a multi-line debug helper), add its name to
`Extractor.SKIP_METHOD_NAMES` or rename the heuristic threshold.

## Re-running on a live codebase

The tool is **idempotent and safe to re-run**. After the first extraction:

- Existing `messages_en.properties` is loaded and preserved.
- Already-extracted call sites (`I18n.tr(...)`) are skipped — `tr` isn't a UI sink.
- New literals added by developers are extracted on the next run; identical
  patterns reuse existing keys via reverse-bundle dedup.

Recommended workflow for ongoing maintenance:

1. **Prevention (new code):** Enable IntelliJ's *Hardcoded strings* inspection
   project-wide and ship the inspection profile in `.idea/inspectionProfiles/`.
   Wire `idea.sh inspect` into CI to fail PRs that introduce new literals.
2. **Periodic sweep:** Run `gradle run --args="<folder>"` against each module
   monthly (or on demand). Review the diff and commit.
3. **CI guard:** `gradle run --args="<folder> --check"` exits non-zero if there
   are unextracted strings, orphan keys, or missing translations. Wire it into
   pipelines as a gating step.

## Maintenance reports (run on every invocation)

The tool emits three reports after every run:

**Orphan keys** — bundle entries with no `I18n.tr(...)` reference in source.
Usually caused by a UI element being deleted without removing the bundle entry.
Reviewed manually; not auto-pruned (too risky).

For dynamic keys (computed at runtime) that legitimately have no static
reference, annotate with `//$NLS-KEEP$` to silence the warning:

```java
//$NLS-KEEP$ Status.READY, Status.ERROR, Status.PENDING
String key = "Status." + status.name();
return I18n.tr(key);
```

**Unresolved keys** — the mirror image of orphan keys: a key passed to
`I18n.tr("literal", ...)` that has **no entry in the bundle yet**. This happens
when a developer hand-writes the key ahead of (or instead of) extraction, e.g.

```java
JOptionPane.showMessageDialog(this, I18n.tr("DataCaptureUploadPane.success"), ...);
```

with no `DataCaptureUploadPane.success` line in `messages_en.properties`. At
runtime the `I18n.tr` helper would fall back to rendering the raw key string to
the user, so these are real defects.

Unlike orphans, unresolved keys are **auto-filled** (outside `--dry-run`): the
key is added to the source bundle with a best-effort English value derived from
the key itself —

- a leading `ClassName.` segment is dropped, the remaining dot-words are
  title-cased and space-joined (`DataCaptureUploadPane.select.file` → `Select File`);
- one `{0..n-1}` `MessageFormat` placeholder is appended per call argument, using
  the **maximum** arity seen across all call sites
  (`I18n.tr("…failure.on.step", id)` → `Failure On Step {0}`).

The values are deliberately rough — a sensible starting point to refine in the
bundle, not a finished translation. They show up as ordinary additions in the
bundle diff for review. Only string-literal first arguments are considered;
computed keys are skipped (use `//$NLS-KEEP$` for those). In `--dry-run` /
`--check` the keys are reported but not written, and `--check` exits non-zero
when any remain.

**Missing translations** — keys present in `messages_en.properties` but absent
or blank in any sibling `messages_*.properties` file. Gives the translator a
clear worklist after each extraction sweep. The tool reports only — translation
itself is out of scope. (Auto-filled unresolved keys are folded into this report
too, so a freshly hand-written key surfaces as a translation gap in the other
locales on the same run.)

## When English text changes

Once a string is extracted, the bundle is the source of truth. Edits go there,
not in source code. There's no automatic way to flag the corresponding
non-source-locale entries as stale when the English value changes — this is an
inherent property of key-based ResourceBundle and not specific to this tool.

Two practical options for handling this:

1. **Rename the key** when the meaning changes substantially. The tool will
   report the old key as orphan and the new key as missing in non-source
   locales. Translators see both signals on the next run.
2. **Edit the value in place** when the meaning is unchanged. Document this in
   the commit message so translators can choose to update or skip.

For audit-grade tracking of which translations correspond to which English
versions, you'd need a TMS (Crowdin, Lokalise, POEditor) — they hash source
strings and flag stale translations automatically. Worth the cost only if you
have many languages or strict regulatory translation requirements.

## Heuristics

A literal is considered user-facing if it:
- contains at least 2 letters, length ≥ 2, not blank
- is not a property-key shape (`lowercase.dot.notation`)
- is not a SCREAMING_CONSTANT
- doesn't contain SQL keywords (SELECT, FROM, INSERT INTO, …)
- doesn't start with `/`, `http://`, `https://`, `file://`, `jdbc:`

UI sinks recognised: `setText`, `setTitle`, `setToolTipText`, `setLabel`,
`showMessageDialog`, `showConfirmDialog`, `showInputDialog`, `showOptionDialog`,
`createTitledBorder`, `createEtchedBorder`, `addTab`, `insertTab`,
`setTitleAt`. Edit `Extractor.UI_METHODS` to add more.

## Multi-statement StringBuilder safety

The dataflow detector refuses extraction (leaves source untouched) when:
- the variable isn't a local declared in the same method
- the initialiser isn't `new StringBuilder()` / `new StringBuffer()`
- any reference is something other than `.append()` / `.toString()`
- any reference lives in a different `BlockStmt` (so loops, ifs, try blocks
  all bail out)
- a second `.toString()` exists (the variable is consumed twice)

Failed extractions are silent — review the file to see what didn't transform.

## Limitations / known gaps

- Cross-method builders (passing a StringBuilder to a helper that appends to
  it) — not detected, refuses extraction.
- Aliasing (`StringBuilder ref = sb; ref.append(...)`) — appends via the
  alias are silently missed; review by hand.
- Pre-existing `import` of a different `I18n` class will collide; the
  generated import goes to `<helper-package>.I18n` regardless.
- Width / precision in printf specs (`%5.2f`) is dropped; hand-edit the
  bundle to `{0,number,#,##0.00}` if needed.
- Plurals: stdlib `MessageFormat.ChoiceFormat` works but is ugly. ICU
  MessageFormat support is out of scope.

## Re-running

Re-running on already-extracted code is safe: existing bundle entries are
preserved and reused (deduplication by pattern). New strings get appended.
Translations in `messages_<other-locale>.properties` are not touched.