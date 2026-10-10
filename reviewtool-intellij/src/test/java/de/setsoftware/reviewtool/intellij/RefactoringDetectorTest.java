package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import de.setsoftware.reviewtool.intellij.RefactoringDetector.FileVersions;
import de.setsoftware.reviewtool.intellij.RefactoringDetector.Refactoring;

/**
 * Tests for {@link RefactoringDetector}.
 */
public class RefactoringDetectorTest {

    private static final String CALCULATOR =
            "package demo;\n"
            + "public class Calculator {\n"
            + "    private int count;\n"
            + "    public Calculator(int start) {\n"
            + "        this.count = start;\n"
            + "    }\n"
            + "    public int add(int a, int b) {\n"
            + "        this.count++;\n"
            + "        return a + b;\n"
            + "    }\n"
            + "    public int multiply(int a, int b) {\n"
            + "        int result = 0;\n"
            + "        for (int i = 0; i < b; i++) {\n"
            + "            result = this.add(result, a);\n"
            + "        }\n"
            + "        return result;\n"
            + "    }\n"
            + "}\n";

    private static Refactoring r(String type, String before, String after) {
        return new Refactoring(type, before, after);
    }

    private static List<Refactoring> detect(FileVersions... files) {
        return RefactoringDetector.detect(Arrays.asList(files));
    }

    @Test
    public void testRenameMethod() {
        final String changed = CALCULATOR.replace("multiply(", "times(");
        assertEquals(Collections.singletonList(
                r(RefactoringDetector.RENAME_METHOD, "Calculator.multiply(int, int)", "Calculator.times(int, int)")),
                detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, changed)));
    }

    @Test
    public void testChangeMethodSignature() {
        final String changed = CALCULATOR.replace("multiply(int a, int b)", "multiply(int a, long b)");
        assertEquals(Collections.singletonList(r(RefactoringDetector.CHANGE_SIGNATURE,
                "Calculator.multiply(int, int)", "Calculator.multiply(int, long)")),
                detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, changed)));
    }

    @Test
    public void testRenameClassIncludingConstructor() {
        final String changed = CALCULATOR.replace("Calculator", "Computer");
        assertEquals(Collections.singletonList(
                r(RefactoringDetector.RENAME_CLASS, "demo.Calculator", "demo.Computer")),
                detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, ""),
                        new FileVersions("src/demo/Computer.java", "", changed)));
    }

    @Test
    public void testMoveClass() {
        final String changed = CALCULATOR.replace("package demo;", "package demo.math;");
        assertEquals(Collections.singletonList(
                r(RefactoringDetector.MOVE_CLASS, "demo.Calculator", "demo.math.Calculator")),
                detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, ""),
                        new FileVersions("src/demo/math/Calculator.java", "", changed)));
    }

    @Test
    public void testMoveMethod() {
        final String withoutMultiply = CALCULATOR.substring(0, CALCULATOR.indexOf("    public int multiply"))
                + "}\n";
        final String helperOld = "package demo;\npublic class Helper {\n}\n";
        final String helperNew = "package demo;\npublic class Helper {\n"
                + "    public int multiply(int a, int b) {\n"
                + "        int result = 0;\n"
                + "        for (int i = 0; i < b; i++) {\n"
                + "            result = this.add(result, a);\n"
                + "        }\n"
                + "        return result;\n"
                + "    }\n"
                + "}\n";
        assertEquals(Collections.singletonList(
                r(RefactoringDetector.MOVE_METHOD, "Calculator.multiply(int, int)", "Helper.multiply(int, int)")),
                detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, withoutMultiply),
                        new FileVersions("src/demo/Helper.java", helperOld, helperNew)));
    }

    @Test
    public void testExtractMethod() {
        final String changed = CALCULATOR.replace(
                "        int result = 0;\n"
                + "        for (int i = 0; i < b; i++) {\n"
                + "            result = this.add(result, a);\n"
                + "        }\n"
                + "        return result;\n"
                + "    }\n",
                "        return this.repeatedAdd(a, b);\n"
                + "    }\n"
                + "    private int repeatedAdd(int a, int b) {\n"
                + "        int result = 0;\n"
                + "        for (int i = 0; i < b; i++) {\n"
                + "            result = this.add(result, a);\n"
                + "        }\n"
                + "        return result;\n"
                + "    }\n");
        final List<Refactoring> refactorings = detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, changed));
        assertEquals(Collections.singletonList(r(RefactoringDetector.EXTRACT_METHOD,
                "Calculator.multiply(int, int)", "Calculator.repeatedAdd(int, int)")), refactorings);

        // the reverse is an inlined method
        assertEquals(Collections.singletonList(r(RefactoringDetector.INLINE_METHOD,
                "Calculator.repeatedAdd(int, int)", "Calculator.multiply(int, int)")),
                detect(new FileVersions("src/demo/Calculator.java", changed, CALCULATOR)));
    }

    @Test
    public void testNoRefactoringsForNewCodeOrSyntaxErrors() {
        final String changed = CALCULATOR.replace("    public int multiply",
                "    public int subtract(int a, int b) {\n        return a - b;\n    }\n    public int multiply");
        assertTrue(detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, changed)).isEmpty());
        assertTrue(detect(new FileVersions("src/demo/Calculator.java", CALCULATOR, "class {")).isEmpty());
        assertTrue(detect(new FileVersions("README.md", "a", "b")).isEmpty());
    }

}
