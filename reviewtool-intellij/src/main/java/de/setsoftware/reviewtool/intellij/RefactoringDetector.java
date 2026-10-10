package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.printer.PrettyPrinterConfiguration;

import de.setsoftware.reviewtool.base.Logger;

/**
 * Detects refactorings in the changed Java files of a review, a lightweight counterpart of the RefDiff
 * based technique of the Eclipse summary view (which needs Eclipse JDT). The old and new versions of
 * the files are parsed with JavaParser, the declarations that only exist in one of the versions are
 * matched by the similarity of their code, and the matches are classified: renamed/moved classes,
 * renamed/moved methods, changed method signatures, extracted and inlined methods. Knowing that a
 * change is "just" a refactoring helps the reviewer to focus on the changes that matter.
 */
final class RefactoringDetector {

    /**
     * The old and the new content of a changed file (empty if the file did not exist).
     */
    static final class FileVersions {
        private final String path;
        private final String oldContent;
        private final String newContent;

        FileVersions(String path, String oldContent, String newContent) {
            this.path = path;
            this.oldContent = oldContent == null ? "" : oldContent;
            this.newContent = newContent == null ? "" : newContent;
        }
    }

    /**
     * A detected refactoring.
     */
    static final class Refactoring {
        private final String type;
        private final String before;
        private final String after;
        private final String afterPath;
        private final int afterLine;

        Refactoring(String type, String before, String after) {
            this(type, before, after, null, 0);
        }

        Refactoring(String type, String before, String after, String afterPath, int afterLine) {
            this.type = type;
            this.before = before;
            this.after = after;
            this.afterPath = afterPath;
            this.afterLine = afterLine;
        }

        /**
         * The path of the file with the declaration after the refactoring (as given in the
         * {@link FileVersions}), or null if unknown.
         */
        String getAfterPath() {
            return this.afterPath;
        }

        /**
         * The (1-based) line of the declaration after the refactoring, or 0 if unknown.
         */
        int getAfterLine() {
            return this.afterLine;
        }

        String getType() {
            return this.type;
        }

        String getBefore() {
            return this.before;
        }

        String getAfter() {
            return this.after;
        }

        @Override
        public String toString() {
            return this.type + ": " + this.before + " -> " + this.after;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Refactoring)) {
                return false;
            }
            final Refactoring r = (Refactoring) o;
            return this.type.equals(r.type) && this.before.equals(r.before) && this.after.equals(r.after);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.type, this.before, this.after);
        }
    }

    static final String RENAME_CLASS = "Rename class";
    static final String MOVE_CLASS = "Move class";
    static final String MOVE_AND_RENAME_CLASS = "Move and rename class";
    static final String RENAME_METHOD = "Rename method";
    static final String CHANGE_SIGNATURE = "Change method signature";
    static final String MOVE_METHOD = "Move method";
    static final String MOVE_AND_RENAME_METHOD = "Move and rename method";
    static final String EXTRACT_METHOD = "Extract method";
    static final String INLINE_METHOD = "Inline method";

    private static final double TYPE_SIMILARITY = 0.6;
    private static final double SIGNATURE_SIMILARITY = 0.5;
    private static final double RENAME_SIMILARITY = 0.75;
    private static final double MOVE_AND_RENAME_SIMILARITY = 0.85;
    private static final double EXTRACT_CONTAINMENT = 0.75;
    private static final int MIN_TOKENS_FOR_MOVE = 6;
    private static final String CONSTRUCTOR = "<init>";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*|\\d+|\\S");
    private static final PrettyPrinterConfiguration NO_COMMENTS =
            new PrettyPrinterConfiguration().setPrintComments(false);

    /**
     * A type (class, interface, enum) in one of the versions.
     */
    private static final class TypeInfo {
        private final String qualifiedName;
        private final String packageName;
        private final String simpleName;
        private final String parent;
        private final Map<String, Integer> tokens;
        private final String path;
        private final int line;

        TypeInfo(String qualifiedName, String packageName, String simpleName, String parent,
                Map<String, Integer> tokens, String path, int line) {
            this.qualifiedName = qualifiedName;
            this.packageName = packageName;
            this.simpleName = simpleName;
            this.parent = parent;
            this.tokens = tokens;
            this.path = path;
            this.line = line;
        }
    }

    /**
     * A method or constructor in one of the versions.
     */
    private static final class MethodInfo {
        private final String type;
        private final String name;
        private final String signature;
        private final Map<String, Integer> tokens;
        private final int tokenCount;
        private final Set<String> calledMethods;
        private final String path;
        private final int line;

        MethodInfo(String type, String name, String signature, Map<String, Integer> tokens,
                Set<String> calledMethods, String path, int line) {
            this.type = type;
            this.name = name;
            this.signature = signature;
            this.tokens = tokens;
            this.tokenCount = count(tokens);
            this.calledMethods = calledMethods;
            this.path = path;
            this.line = line;
        }

        String key() {
            return this.type + "#" + this.signature;
        }

        boolean isConstructor() {
            return this.signature.startsWith(CONSTRUCTOR);
        }

        String display() {
            final String simpleType = simpleTypeName(this.type);
            return this.isConstructor()
                    ? simpleType + "." + simpleType + this.signature.substring(CONSTRUCTOR.length())
                    : simpleType + "." + this.signature;
        }
    }

    /**
     * All types and methods of one version.
     */
    private static final class Model {
        private final Map<String, TypeInfo> types = new LinkedHashMap<>();
        private final Map<String, MethodInfo> methods = new LinkedHashMap<>();
    }

    private RefactoringDetector() {
    }

    /**
     * Detects the refactorings between the old and the new versions of the given files.
     */
    static List<Refactoring> detect(Collection<FileVersions> files) {
        final Model oldModel = new Model();
        final Model newModel = new Model();
        for (final FileVersions file : files) {
            if (!file.path.endsWith(".java")) {
                continue;
            }
            parseInto(file.path, file.oldContent, oldModel);
            parseInto(file.path, file.newContent, newModel);
        }

        final List<Refactoring> ret = new ArrayList<>();
        final Map<String, String> typeMapping = matchTypes(oldModel, newModel, ret);
        final Set<String> matchedOld = new HashSet<>();
        final Set<String> matchedNew = new HashSet<>();
        matchMethods(oldModel, newModel, typeMapping, matchedOld, matchedNew, ret);
        detectExtractAndInline(oldModel, newModel, typeMapping, matchedOld, matchedNew, ret);
        return ret;
    }

    private static void parseInto(String path, String content, Model model) {
        if (content.isEmpty()) {
            return;
        }
        try {
            final CompilationUnit cu = JavaParser.parse(content);
            final String pkg = cu.getPackageDeclaration().map((p) -> p.getNameAsString()).orElse("");
            for (final TypeDeclaration<?> type : cu.getTypes()) {
                addType(path, pkg, pkg.isEmpty() ? "" : pkg + ".", null, type, model);
            }
        } catch (final RuntimeException e) {
            //a file with syntax errors is just ignored for the refactoring detection
            Logger.debug("could not parse " + path + " for refactoring detection: " + e);
        }
    }

    private static void addType(String path, String pkg, String prefix, String parent, TypeDeclaration<?> type,
            Model model) {
        final String qualifiedName = prefix + type.getNameAsString();
        model.types.put(qualifiedName, new TypeInfo(
                qualifiedName, pkg, type.getNameAsString(), parent, tokens(type), path, line(type.getName())));
        for (final BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration) {
                addType(path, pkg, qualifiedName + ".", qualifiedName, (TypeDeclaration<?>) member, model);
            } else if (member instanceof MethodDeclaration || member instanceof ConstructorDeclaration) {
                final CallableDeclaration<?> callable = (CallableDeclaration<?>) member;
                final Node body = member instanceof MethodDeclaration
                        ? ((MethodDeclaration) member).getBody().map((b) -> (Node) b).orElse(null)
                        : ((ConstructorDeclaration) member).getBody();
                final Set<String> called = new HashSet<>();
                if (body != null) {
                    for (final MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
                        called.add(call.getNameAsString());
                    }
                }
                final MethodInfo info = new MethodInfo(qualifiedName, callable.getNameAsString(),
                        signature(callable), body == null ? new HashMap<>() : tokens(body), called,
                        path, line(callable.getName()));
                model.methods.put(info.key(), info);
            }
        }
    }

    private static int line(Node node) {
        return node.getBegin().map((p) -> p.line).orElse(0);
    }

    private static String signature(CallableDeclaration<?> callable) {
        final List<String> params = new ArrayList<>();
        for (final Parameter p : callable.getParameters()) {
            params.add(p.getType().asString() + (p.isVarArgs() ? "..." : ""));
        }
        final String name = callable instanceof ConstructorDeclaration ? CONSTRUCTOR : callable.getNameAsString();
        return name + "(" + String.join(", ", params) + ")";
    }

    private static Map<String, Integer> tokens(Node node) {
        final Map<String, Integer> ret = new HashMap<>();
        final Matcher m = TOKEN.matcher(node.toString(NO_COMMENTS));
        while (m.find()) {
            ret.merge(m.group(), 1, Integer::sum);
        }
        return ret;
    }

    private static int count(Map<String, Integer> tokens) {
        int sum = 0;
        for (final int c : tokens.values()) {
            sum += c;
        }
        return sum;
    }

    private static int intersection(Map<String, Integer> a, Map<String, Integer> b) {
        int common = 0;
        for (final Map.Entry<String, Integer> e : a.entrySet()) {
            final Integer other = b.get(e.getKey());
            if (other != null) {
                common += Math.min(e.getValue(), other);
            }
        }
        return common;
    }

    /**
     * Dice coefficient of the two token multisets.
     */
    static double similarity(Map<String, Integer> a, Map<String, Integer> b) {
        final int total = count(a) + count(b);
        if (total == 0) {
            return 1.0;
        }
        return 2.0 * intersection(a, b) / total;
    }

    private static String simpleTypeName(String qualifiedName) {
        final int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    /**
     * Matches the types that only exist in one of the versions and returns the mapping from old to new
     * type names (including the unchanged types).
     */
    private static Map<String, String> matchTypes(Model oldModel, Model newModel, List<Refactoring> ret) {
        final Map<String, String> mapping = new HashMap<>();
        for (final String name : oldModel.types.keySet()) {
            if (newModel.types.containsKey(name)) {
                mapping.put(name, name);
            }
        }
        // types are processed outside-in, so that nested types of moved types are mapped implicitly
        final Set<String> usedNew = new HashSet<>(mapping.values());
        for (final TypeInfo oldType : oldModel.types.values()) {
            if (mapping.containsKey(oldType.qualifiedName)) {
                continue;
            }
            if (oldType.parent != null && mapping.containsKey(oldType.parent)) {
                final String implicit = mapping.get(oldType.parent) + "." + oldType.simpleName;
                if (newModel.types.containsKey(implicit) && !usedNew.contains(implicit)) {
                    mapping.put(oldType.qualifiedName, implicit);
                    usedNew.add(implicit);
                    continue;
                }
            }
            TypeInfo best = null;
            double bestSimilarity = TYPE_SIMILARITY;
            for (final TypeInfo newType : newModel.types.values()) {
                if (usedNew.contains(newType.qualifiedName) || oldModel.types.containsKey(newType.qualifiedName)) {
                    continue;
                }
                final double sim = similarity(oldType.tokens, newType.tokens);
                if (sim >= bestSimilarity) {
                    best = newType;
                    bestSimilarity = sim;
                }
            }
            if (best == null) {
                continue;
            }
            mapping.put(oldType.qualifiedName, best.qualifiedName);
            usedNew.add(best.qualifiedName);
            final boolean samePackage = oldType.packageName.equals(best.packageName)
                    && Objects.equals(oldType.parent == null ? null : mapping.get(oldType.parent), best.parent);
            final boolean sameName = oldType.simpleName.equals(best.simpleName);
            final String kind = samePackage ? RENAME_CLASS : (sameName ? MOVE_CLASS : MOVE_AND_RENAME_CLASS);
            ret.add(new Refactoring(kind, oldType.qualifiedName, best.qualifiedName, best.path, best.line));
        }
        return mapping;
    }

    /**
     * Matches the methods that only exist in one of the versions (renamed, moved or with changed signature).
     */
    private static void matchMethods(Model oldModel, Model newModel, Map<String, String> typeMapping,
            Set<String> matchedOld, Set<String> matchedNew, List<Refactoring> ret) {
        for (final MethodInfo oldMethod : oldModel.methods.values()) {
            final String mappedType = typeMapping.get(oldMethod.type);
            if (mappedType != null && newModel.methods.containsKey(mappedType + "#" + oldMethod.signature)) {
                matchedOld.add(oldMethod.key());
                matchedNew.add(mappedType + "#" + oldMethod.signature);
            }
        }
        // determine all candidate pairs and take them greedily, best similarity first
        final List<Object[]> candidates = new ArrayList<>();
        for (final MethodInfo oldMethod : oldModel.methods.values()) {
            if (matchedOld.contains(oldMethod.key())) {
                continue;
            }
            final String mappedType = typeMapping.get(oldMethod.type);
            for (final MethodInfo newMethod : newModel.methods.values()) {
                if (matchedNew.contains(newMethod.key())) {
                    continue;
                }
                final double sim = similarity(oldMethod.tokens, newMethod.tokens);
                final String kind = classifyMethodChange(oldMethod, newMethod, mappedType, sim);
                if (kind != null) {
                    candidates.add(new Object[] {sim, oldMethod, newMethod, kind});
                }
            }
        }
        candidates.sort((a, b) -> Double.compare((Double) b[0], (Double) a[0]));
        for (final Object[] c : candidates) {
            final MethodInfo oldMethod = (MethodInfo) c[1];
            final MethodInfo newMethod = (MethodInfo) c[2];
            if (matchedOld.contains(oldMethod.key()) || matchedNew.contains(newMethod.key())) {
                continue;
            }
            matchedOld.add(oldMethod.key());
            matchedNew.add(newMethod.key());
            ret.add(new Refactoring((String) c[3], oldMethod.display(), newMethod.display(),
                    newMethod.path, newMethod.line));
        }
    }

    private static String classifyMethodChange(MethodInfo oldMethod, MethodInfo newMethod, String mappedType,
            double sim) {
        final boolean sameType = newMethod.type.equals(mappedType);
        final boolean sameName = oldMethod.name.equals(newMethod.name);
        if (sameType) {
            if (sameName) {
                return sim >= SIGNATURE_SIMILARITY ? CHANGE_SIGNATURE : null;
            }
            return sim >= RENAME_SIMILARITY && oldMethod.tokenCount > 0
                    && !oldMethod.isConstructor() && !newMethod.isConstructor() ? RENAME_METHOD : null;
        }
        if (oldMethod.tokenCount < MIN_TOKENS_FOR_MOVE || oldMethod.isConstructor() || newMethod.isConstructor()) {
            return null;
        }
        if (sameName) {
            return sim >= RENAME_SIMILARITY ? MOVE_METHOD : null;
        }
        return sim >= MOVE_AND_RENAME_SIMILARITY ? MOVE_AND_RENAME_METHOD : null;
    }

    /**
     * Detects extracted methods (a new method whose code was part of an existing method that now calls
     * it) and inlined methods (the reverse).
     */
    private static void detectExtractAndInline(Model oldModel, Model newModel, Map<String, String> typeMapping,
            Set<String> matchedOld, Set<String> matchedNew, List<Refactoring> ret) {
        final Map<String, String> reverseTypeMapping = new HashMap<>();
        for (final Map.Entry<String, String> e : typeMapping.entrySet()) {
            reverseTypeMapping.put(e.getValue(), e.getKey());
        }
        final List<MethodInfo[]> persistent = new ArrayList<>();
        for (final MethodInfo oldMethod : oldModel.methods.values()) {
            final String mappedType = typeMapping.get(oldMethod.type);
            final MethodInfo newMethod = mappedType == null
                    ? null : newModel.methods.get(mappedType + "#" + oldMethod.signature);
            if (newMethod != null) {
                persistent.add(new MethodInfo[] {oldMethod, newMethod});
            }
        }
        for (final MethodInfo added : newModel.methods.values()) {
            if (matchedNew.contains(added.key()) || added.tokenCount == 0 || added.isConstructor()) {
                continue;
            }
            for (final MethodInfo[] pair : persistent) {
                if (!pair[1].calledMethods.contains(added.name) || pair[0].calledMethods.contains(added.name)) {
                    continue;
                }
                if (containment(added.tokens, difference(pair[0].tokens, pair[1].tokens)) >= EXTRACT_CONTAINMENT) {
                    ret.add(new Refactoring(EXTRACT_METHOD, pair[0].display(), added.display(),
                            added.path, added.line));
                    break;
                }
            }
        }
        for (final MethodInfo removed : oldModel.methods.values()) {
            if (matchedOld.contains(removed.key()) || removed.tokenCount == 0 || removed.isConstructor()) {
                continue;
            }
            for (final MethodInfo[] pair : persistent) {
                if (!pair[0].calledMethods.contains(removed.name) || pair[1].calledMethods.contains(removed.name)) {
                    continue;
                }
                if (containment(removed.tokens, difference(pair[1].tokens, pair[0].tokens)) >= EXTRACT_CONTAINMENT) {
                    ret.add(new Refactoring(INLINE_METHOD, removed.display(), pair[1].display(),
                            pair[1].path, pair[1].line));
                    break;
                }
            }
        }
    }

    /**
     * The tokens that are in "a" but not in "b" (multiset difference).
     */
    private static Map<String, Integer> difference(Map<String, Integer> a, Map<String, Integer> b) {
        final Map<String, Integer> ret = new HashMap<>();
        for (final Map.Entry<String, Integer> e : a.entrySet()) {
            final int diff = e.getValue() - b.getOrDefault(e.getKey(), 0);
            if (diff > 0) {
                ret.put(e.getKey(), diff);
            }
        }
        return ret;
    }

    /**
     * The share of the tokens of "part" (without braces) that are contained in "whole".
     */
    private static double containment(Map<String, Integer> part, Map<String, Integer> whole) {
        final Map<String, Integer> relevant = new HashMap<>(part);
        relevant.remove("{");
        relevant.remove("}");
        relevant.remove("return");
        final int total = count(relevant);
        return total == 0 ? 0.0 : (double) intersection(relevant, whole) / total;
    }

}
