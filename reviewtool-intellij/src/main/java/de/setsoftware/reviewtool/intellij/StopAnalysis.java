package de.setsoftware.reviewtool.intellij;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import de.setsoftware.reviewtool.base.Logger;

/**
 * Determines the context of the stops of a review that reviewers need besides the diff: which declarations a
 * stop changes, which other stops it calls or is called from, which stops are tests and what they test, and
 * whether a stop is explained by a detected refactoring (e.g. all its changed lines are the renaming of a
 * method). It works on the texts of the stops' files (parsed with JavaParser), without the IDE's index:
 * calls are matched to the changed declarations by name, which is a heuristic.
 */
final class StopAnalysis {

    /**
     * A stop to analyze.
     */
    static final class Input {
        private final Object id;
        private final int group;
        private final String path;
        private final String oldText;
        private final String newText;
        private final List<StopDiffExcerpt.Change> changes;
        private final boolean test;

        /**
         * @param id Identifies the stop in the result (e.g. the stop itself).
         * @param group The refactorings are detected per group (e.g. per tour, since the tours can
         *      contain different versions of a file).
         * @param path The path of the file.
         * @param changes The changes of the stop (between the old and the new text).
         * @param test True if the file is a test.
         */
        Input(Object id, int group, String path, String oldText, String newText,
                List<StopDiffExcerpt.Change> changes, boolean test) {
            this.id = id;
            this.group = group;
            this.path = path;
            this.oldText = oldText == null ? "" : oldText;
            this.newText = newText == null ? "" : newText;
            this.changes = changes;
            this.test = test;
        }
    }

    /**
     * A relation of a stop to another stop, with the name of the declaration or file that relates them (e.g.
     * "Calculator.sum" for a call).
     */
    static final class Link {
        private final Object target;
        private final String text;

        Link(Object target, String text) {
            this.target = target;
            this.text = text;
        }

        Object getTarget() {
            return this.target;
        }

        String getText() {
            return this.text;
        }

        @Override
        public String toString() {
            return this.text + " -> " + this.target;
        }
    }

    /**
     * The result for one stop.
     */
    static final class Info {
        private final boolean test;
        private final List<String> declarations = new ArrayList<>();
        private final Set<String> typeNames = new LinkedHashSet<>();
        private final List<Link> calls = new ArrayList<>();
        private final List<Link> calledBy = new ArrayList<>();
        private final List<Link> tests = new ArrayList<>();
        private final List<Link> tested = new ArrayList<>();
        private final List<String> refactorings = new ArrayList<>();
        private boolean refactoringOnly;

        Info(boolean test) {
            this.test = test;
        }

        /** True if the stop is in a test. */
        boolean isTest() {
            return this.test;
        }

        /** The changed declarations, e.g. "Calculator.sum" (a type if no method is changed). */
        List<String> getDeclarations() {
            return this.declarations;
        }

        /** The (simple) names of the types declared in the stop's file. */
        Set<String> getTypeNames() {
            return this.typeNames;
        }

        /** The stops with declarations that the changed lines of this stop call. */
        List<Link> getCalls() {
            return this.calls;
        }

        /** The stops whose changed lines call a declaration changed in this stop. */
        List<Link> getCalledBy() {
            return this.calledBy;
        }

        /** For a production stop: the test stops that use its types. */
        List<Link> getTests() {
            return this.tests;
        }

        /** For a test stop: the production stops whose types it uses. */
        List<Link> getTested() {
            return this.tested;
        }

        /** The detected refactorings the stop is part of, e.g. "Rename method: A.b() → A.c()". */
        List<String> getRefactorings() {
            return this.refactorings;
        }

        /** True if all changed lines of the stop are explained by the renamings of its refactorings. */
        boolean isRefactoringOnly() {
            return this.refactoringOnly;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*|\\d+|\\S");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern TEST_NAME = Pattern.compile("^(.+?)Tests?$|^Test([A-Z].*)$");

    /**
     * A method, constructor or type of a parsed file with its range of lines (1-based, inclusive).
     */
    private static final class Declaration {
        private final String name;
        private final String display;
        private final int begin;
        private final int end;
        private final boolean type;

        Declaration(String name, String display, int begin, int end, boolean type) {
            this.name = name;
            this.display = display;
            this.begin = begin;
            this.end = end;
            this.type = type;
        }

        boolean contains(int line) {
            return this.begin <= line && line <= this.end;
        }
    }

    /**
     * A call of a method or constructor (by name) in a line of a parsed file.
     */
    private static final class Call {
        private final String name;
        private final int line;

        Call(String name, int line) {
            this.name = name;
            this.line = line;
        }
    }

    /**
     * The declarations, calls and identifiers of a file.
     */
    private static final class FileModel {
        private final List<Declaration> declarations = new ArrayList<>();
        private final List<Call> calls = new ArrayList<>();
        private final Set<String> typeNames = new LinkedHashSet<>();
        private final Set<String> identifiers = new HashSet<>();

        /**
         * The innermost method (or else type) that contains the given line, or null.
         */
        Declaration innermostAt(int line) {
            Declaration best = null;
            for (final Declaration d : this.declarations) {
                if (!d.contains(line)) {
                    continue;
                }
                if (best == null || (best.type && !d.type)
                        || (best.type == d.type && d.end - d.begin < best.end - best.begin)) {
                    best = d;
                }
            }
            return best;
        }
    }

    private StopAnalysis() {
    }

    /**
     * Analyzes the given stops. The result contains an entry for each input (in the same order).
     */
    static Map<Object, Info> analyze(List<Input> inputs) {
        final Map<Object, Info> ret = new LinkedHashMap<>();
        final Map<String, FileModel> models = new HashMap<>();
        final Map<Object, Set<Integer>> changedLines = new HashMap<>();
        for (final Input input : inputs) {
            final Info info = new Info(input.test);
            ret.put(input.id, info);
            final FileModel model = models.computeIfAbsent(input.path + '\n' + input.newText,
                    (k) -> parse(input.path, input.newText));
            info.typeNames.addAll(model.typeNames);
            final Set<Integer> lines = changedNewLines(input.changes);
            changedLines.put(input.id, lines);
            // the changed methods, or the type if only lines outside of methods are changed
            final Set<String> methods = new LinkedHashSet<>();
            final Set<String> types = new LinkedHashSet<>();
            for (final int line : lines.isEmpty() ? deletionAnchors(input.changes) : lines) {
                final Declaration d = model.innermostAt(line);
                if (d != null) {
                    (d.type ? types : methods).add(d.display);
                }
            }
            info.declarations.addAll(methods.isEmpty() ? types : methods);
        }
        linkCalls(inputs, ret, models, changedLines);
        linkTests(inputs, ret, models);
        detectRefactorings(inputs, ret);
        return ret;
    }

    /**
     * The changed lines of the new text (1-based).
     */
    private static Set<Integer> changedNewLines(List<StopDiffExcerpt.Change> changes) {
        final Set<Integer> ret = new TreeSet<>();
        for (final StopDiffExcerpt.Change c : changes) {
            for (int i = c.getNewStart(); i < c.getNewEnd(); i++) {
                ret.add(i + 1);
            }
        }
        return ret;
    }

    /**
     * The lines next to deleted lines (1-based), for stops that only delete lines.
     */
    private static Set<Integer> deletionAnchors(List<StopDiffExcerpt.Change> changes) {
        final Set<Integer> ret = new TreeSet<>();
        for (final StopDiffExcerpt.Change c : changes) {
            ret.add(Math.max(1, c.getNewStart()));
        }
        return ret;
    }

    private static FileModel parse(String path, String text) {
        final FileModel model = new FileModel();
        final Matcher m = IDENTIFIER.matcher(text);
        while (m.find()) {
            model.identifiers.add(m.group());
        }
        if (!path.endsWith(".java") || text.isEmpty()) {
            return model;
        }
        try {
            final CompilationUnit cu = JavaParser.parse(text);
            for (final TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                model.typeNames.add(type.getNameAsString());
                model.declarations.add(new Declaration(type.getNameAsString(), type.getNameAsString(),
                        begin(type), end(type), true));
            }
            for (final CallableDeclaration<?> callable : cu.findAll(CallableDeclaration.class)) {
                final String typeName = enclosingTypeName(callable);
                model.declarations.add(new Declaration(callable.getNameAsString(),
                        (typeName == null ? "" : typeName + ".") + callable.getNameAsString(),
                        begin(callable), end(callable), false));
            }
            for (final MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                model.calls.add(new Call(call.getNameAsString(), begin(call.getName())));
            }
            for (final ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                model.calls.add(new Call(creation.getType().getNameAsString(), begin(creation)));
            }
        } catch (final RuntimeException e) {
            // a file with syntax errors just has no declarations and calls
            Logger.debug("could not parse " + path + " for the stop context: " + e);
        }
        return model;
    }

    private static int begin(Node node) {
        return node.getBegin().map((p) -> p.line).orElse(0);
    }

    private static int end(Node node) {
        return node.getEnd().map((p) -> p.line).orElse(0);
    }

    private static String enclosingTypeName(Node node) {
        for (Node cur = node.getParentNode().orElse(null); cur != null; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof TypeDeclaration) {
                return ((TypeDeclaration<?>) cur).getNameAsString();
            }
        }
        return null;
    }

    /**
     * Links the stops whose changed lines call a method (or constructor) to the other stops that change a
     * declaration with that name.
     */
    private static void linkCalls(List<Input> inputs, Map<Object, Info> infos, Map<String, FileModel> models,
            Map<Object, Set<Integer>> changedLines) {
        // changed method names -> stops and their declarations
        final Map<String, List<Object[]>> changedByName = new HashMap<>();
        for (final Input input : inputs) {
            final FileModel model = models.get(input.path + '\n' + input.newText);
            final Set<String> seen = new HashSet<>();
            for (final int line : changedLines.get(input.id)) {
                final Declaration d = model.innermostAt(line);
                if (d != null && !d.type && seen.add(d.display)) {
                    add(changedByName, d, input.id);
                }
            }
            // a changed type: its constructors (calls by "new Type(...)")
            for (final Declaration d : model.declarations) {
                if (d.type && changedLines.get(input.id).contains(d.begin) && seen.add(d.display)) {
                    add(changedByName, d, input.id);
                }
            }
        }
        for (final Input caller : inputs) {
            final FileModel model = models.get(caller.path + '\n' + caller.newText);
            final Set<Integer> lines = changedLines.get(caller.id);
            final Set<String> linked = new HashSet<>();
            for (final Call call : model.calls) {
                if (!lines.contains(call.line) || !changedByName.containsKey(call.name)) {
                    continue;
                }
                final Declaration from = model.innermostAt(call.line);
                for (final Object[] target : changedByName.get(call.name)) {
                    final Object targetId = target[0];
                    final Declaration d = (Declaration) target[1];
                    if (targetId == caller.id || !linked.add(targetId + "\n" + d.display)) {
                        continue;
                    }
                    infos.get(caller.id).calls.add(new Link(targetId, d.display));
                    infos.get(targetId).calledBy.add(new Link(caller.id,
                            from == null ? fileName(caller.path) : from.display));
                }
            }
        }
    }

    private static void add(Map<String, List<Object[]>> changedByName, Declaration d, Object id) {
        changedByName.computeIfAbsent(d.name, (k) -> new ArrayList<>()).add(new Object[] {id, d});
    }

    /**
     * Links the test stops to the production stops whose types they use (or whose name they carry, like
     * "CalculatorTest" for "Calculator").
     */
    private static void linkTests(List<Input> inputs, Map<Object, Info> infos, Map<String, FileModel> models) {
        for (final Input test : inputs) {
            if (!test.test) {
                continue;
            }
            final FileModel testModel = models.get(test.path + '\n' + test.newText);
            final String testedName = testedName(baseName(test.path));
            for (final Input production : inputs) {
                if (production.test) {
                    continue;
                }
                final Info info = infos.get(production.id);
                String used = null;
                for (final String type : info.typeNames) {
                    if (testModel.identifiers.contains(type)) {
                        used = type;
                        break;
                    }
                }
                if (used == null && baseName(production.path).equals(testedName)) {
                    used = testedName;
                }
                if (used != null) {
                    infos.get(test.id).tested.add(new Link(production.id, used));
                    info.tests.add(new Link(test.id, baseName(test.path)));
                }
            }
        }
    }

    /**
     * The name of the class a test with the given name probably tests ("CalculatorTest" -> "Calculator"),
     * or null.
     */
    static String testedName(String testName) {
        final Matcher m = TEST_NAME.matcher(testName);
        if (!m.matches()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /**
     * Returns true if the path looks like a test (in a test source folder or with a test name).
     */
    static boolean looksLikeTest(String path) {
        final String normalized = path.replace('\\', '/');
        return normalized.contains("/src/test/") || normalized.contains("/test/")
                || normalized.contains("/tests/") || (normalized.endsWith(".java")
                        && testedName(baseName(normalized)) != null);
    }

    static String baseName(String path) {
        final String name = fileName(path);
        final int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String fileName(String path) {
        return new File(path.replace('\\', '/')).getName();
    }

    /**
     * Assigns the detected refactorings (per group) to the stops: a stop is part of a refactoring if its
     * changed lines contain the refactored declaration or are explained by the renaming. A stop is
     * "refactoring only" if replacing the old names by the new ones in its old lines gives its new lines.
     */
    private static void detectRefactorings(List<Input> inputs, Map<Object, Info> infos) {
        final Map<Integer, List<Input>> groups = new LinkedHashMap<>();
        for (final Input input : inputs) {
            groups.computeIfAbsent(input.group, (k) -> new ArrayList<>()).add(input);
        }
        for (final List<Input> group : groups.values()) {
            final Map<String, RefactoringDetector.FileVersions> files = new LinkedHashMap<>();
            for (final Input input : group) {
                files.putIfAbsent(input.path,
                        new RefactoringDetector.FileVersions(input.path, input.oldText, input.newText));
            }
            List<RefactoringDetector.Refactoring> refactorings;
            try {
                refactorings = RefactoringDetector.detect(files.values());
            } catch (final RuntimeException e) {
                Logger.warn("could not detect refactorings for the stops", e);
                refactorings = Collections.emptyList();
            }
            if (refactorings.isEmpty()) {
                continue;
            }
            for (final Input input : group) {
                assignRefactorings(input, infos.get(input.id), refactorings);
            }
        }
    }

    private static void assignRefactorings(Input input, Info info,
            List<RefactoringDetector.Refactoring> refactorings) {
        final String[] oldLines = lines(input.oldText);
        final String[] newLines = lines(input.newText);
        final StringBuilder oldChanged = new StringBuilder();
        final StringBuilder newChanged = new StringBuilder();
        int from = Integer.MAX_VALUE;
        int to = Integer.MIN_VALUE;
        for (final StopDiffExcerpt.Change c : input.changes) {
            for (int i = c.getOldStart(); i < c.getOldEnd() && i < oldLines.length; i++) {
                oldChanged.append(oldLines[i]).append('\n');
            }
            for (int i = c.getNewStart(); i < c.getNewEnd() && i < newLines.length; i++) {
                newChanged.append(newLines[i]).append('\n');
            }
            from = Math.min(from, c.getNewStart() + 1);
            to = Math.max(to, c.getNewEnd());
        }
        final List<String> oldTokens = tokens(oldChanged.toString());
        final List<String> newTokens = tokens(newChanged.toString());
        final Set<String> assigned = new LinkedHashSet<>();
        final Map<String, String> renames = new HashMap<>();
        for (final RefactoringDetector.Refactoring r : refactorings) {
            final String oldName = simpleName(r.getBefore());
            final String newName = simpleName(r.getAfter());
            final boolean renaming = !oldName.equals(newName) && isRenaming(r.getType());
            if (renaming && oldTokens.contains(oldName) && newTokens.contains(newName)) {
                renames.put(oldName, newName);
                assigned.add(describe(r));
            } else if (input.path.equals(r.getAfterPath()) && from <= r.getAfterLine() && r.getAfterLine() <= to) {
                assigned.add(describe(r));
            }
        }
        info.refactorings.addAll(assigned);
        if (renames.isEmpty() || oldTokens.isEmpty()) {
            return;
        }
        final List<String> renamed = new ArrayList<>(oldTokens.size());
        for (final String token : oldTokens) {
            renamed.add(renames.getOrDefault(token, token));
        }
        info.refactoringOnly = renamed.equals(newTokens);
    }

    private static boolean isRenaming(String type) {
        return type.equals(RefactoringDetector.RENAME_METHOD)
                || type.equals(RefactoringDetector.MOVE_AND_RENAME_METHOD)
                || type.equals(RefactoringDetector.RENAME_CLASS)
                || type.equals(RefactoringDetector.MOVE_AND_RENAME_CLASS);
    }

    /**
     * The simple name of a type ("demo.Calculator" -> "Calculator") or method ("Calculator.sum(int)" -> "sum").
     */
    static String simpleName(String name) {
        final int paren = name.indexOf('(');
        final String withoutParams = paren < 0 ? name : name.substring(0, paren);
        final int dot = withoutParams.lastIndexOf('.');
        return dot < 0 ? withoutParams : withoutParams.substring(dot + 1);
    }

    static String describe(RefactoringDetector.Refactoring r) {
        return r.getType() + ": " + r.getBefore() + " → " + r.getAfter();
    }

    private static String[] lines(String text) {
        return text.split("\r?\n", -1);
    }

    private static List<String> tokens(String text) {
        final List<String> ret = new ArrayList<>();
        final Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            ret.add(m.group());
        }
        return ret;
    }

}
