package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link StopAnalysis}.
 */
public class StopAnalysisTest {

    private static final String CALCULATOR_OLD = "package demo;\n"
            + "\n"
            + "public class Calculator {\n"
            + "    public int multiply(int a, int b) {\n"
            + "        return a * b;\n"
            + "    }\n"
            + "}\n";

    private static final String CALCULATOR_NEW = "package demo;\n"
            + "\n"
            + "public class Calculator {\n"
            + "    public int times(int a, int b) {\n"
            + "        return a * b;\n"
            + "    }\n"
            + "\n"
            + "    public int sum(java.util.List<Integer> values) {\n"
            + "        return values.stream().mapToInt(Integer::intValue).sum();\n"
            + "    }\n"
            + "}\n";

    private static final String MAIN_OLD = "package demo;\n"
            + "\n"
            + "public class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        final Calculator c = new Calculator();\n"
            + "        System.out.println(c.multiply(3, 4));\n"
            + "    }\n"
            + "}\n";

    private static final String MAIN_NEW = "package demo;\n"
            + "\n"
            + "public class Main {\n"
            + "    public static void main(String[] args) {\n"
            + "        final Calculator c = new Calculator();\n"
            + "        System.out.println(c.times(3, 4));\n"
            + "        System.out.println(c.sum(java.util.Arrays.asList(1, 2)));\n"
            + "    }\n"
            + "}\n";

    private static final String TEST_NEW = "package demo;\n"
            + "\n"
            + "public class CalculatorTest {\n"
            + "    public static void main(String[] args) {\n"
            + "        check(new Calculator().times(2, 3) == 6);\n"
            + "    }\n"
            + "}\n";

    private static List<StopDiffExcerpt.Change> changes(StopDiffExcerpt.Change... changes) {
        return Arrays.asList(changes);
    }

    private static StopDiffExcerpt.Change change(int oldStart, int oldEnd, int newStart, int newEnd) {
        return new StopDiffExcerpt.Change(oldStart, oldEnd, newStart, newEnd);
    }

    private static Map<Object, StopAnalysis.Info> analyze() {
        return StopAnalysis.analyze(Arrays.asList(
                // the renamed method
                new StopAnalysis.Input("rename", 0, "/p/src/main/java/demo/Calculator.java",
                        CALCULATOR_OLD, CALCULATOR_NEW, changes(change(3, 4, 3, 4)), false),
                // the new method
                new StopAnalysis.Input("sum", 0, "/p/src/main/java/demo/Calculator.java",
                        CALCULATOR_OLD, CALCULATOR_NEW, changes(change(6, 6, 6, 10)), false),
                // the calls: the renamed one and the new one
                new StopAnalysis.Input("renamedCall", 0, "/p/src/main/java/demo/Main.java",
                        MAIN_OLD, MAIN_NEW, changes(change(5, 6, 5, 6)), false),
                new StopAnalysis.Input("newCall", 0, "/p/src/main/java/demo/Main.java",
                        MAIN_OLD, MAIN_NEW, changes(change(6, 6, 6, 7)), false),
                new StopAnalysis.Input("test", 0, "/p/src/test/java/demo/CalculatorTest.java",
                        "", TEST_NEW, changes(change(0, 0, 0, 7)), true)));
    }

    @Test
    public void testDeclarationsAndCalls() {
        final Map<Object, StopAnalysis.Info> infos = analyze();
        assertEquals(Arrays.asList("Calculator.times"), infos.get("rename").getDeclarations());
        assertEquals(Arrays.asList("Calculator.sum"), infos.get("sum").getDeclarations());
        assertEquals(Arrays.asList("Main.main"), infos.get("newCall").getDeclarations());

        assertEquals("[Calculator.sum -> sum]", infos.get("newCall").getCalls().toString());
        assertEquals("[Main.main -> newCall]", infos.get("sum").getCalledBy().toString());
        assertEquals("[Calculator.times -> rename]", infos.get("renamedCall").getCalls().toString());
        // the test calls the renamed method, too
        assertEquals("[Main.main -> renamedCall, CalculatorTest.main -> test]",
                infos.get("rename").getCalledBy().toString());
    }

    @Test
    public void testRefactoringOnlyStops() {
        final Map<Object, StopAnalysis.Info> infos = analyze();
        final String rename = "Rename method: Calculator.multiply(int, int) → Calculator.times(int, int)";
        assertEquals(Arrays.asList(rename), infos.get("rename").getRefactorings());
        assertTrue(infos.get("rename").isRefactoringOnly());
        assertEquals(Arrays.asList(rename), infos.get("renamedCall").getRefactorings());
        assertTrue(infos.get("renamedCall").isRefactoringOnly());
        // new code is no refactoring
        assertEquals(Collections.emptyList(), infos.get("sum").getRefactorings());
        assertFalse(infos.get("sum").isRefactoringOnly());
        assertFalse(infos.get("newCall").isRefactoringOnly());
    }

    @Test
    public void testRenameWithFurtherChangesIsNotRefactoringOnly() {
        final Map<Object, StopAnalysis.Info> infos = StopAnalysis.analyze(Arrays.asList(
                new StopAnalysis.Input("rename", 0, "/p/Calculator.java",
                        CALCULATOR_OLD, CALCULATOR_NEW, changes(change(3, 4, 3, 4)), false),
                new StopAnalysis.Input("calls", 0, "/p/Main.java",
                        MAIN_OLD, MAIN_NEW, changes(change(5, 6, 5, 7)), false)));
        assertEquals(1, infos.get("calls").getRefactorings().size());
        assertFalse(infos.get("calls").isRefactoringOnly());
    }

    @Test
    public void testExtractedMethodIsPartOfARefactoring() {
        final String head = "package demo.stats;\n"
                + "\n"
                + "public class Statistics {\n"
                + "    private final java.util.List<Integer> values = null;\n"
                + "\n"
                + "    public double mean() {\n"
                + "        if (this.values.isEmpty()) {\n"
                + "            return 0.0;\n"
                + "        }\n";
        final String oldText = head
                + "        long sum = 0;\n"
                + "        for (final int v : this.values) {\n"
                + "            sum += v;\n"
                + "        }\n"
                + "        return (double) sum / this.values.size();\n"
                + "    }\n"
                + "}\n";
        final String newText = head
                + "        return (double) this.sum() / this.values.size();\n"
                + "    }\n"
                + "\n"
                + "    private long sum() {\n"
                + "        long sum = 0;\n"
                + "        for (final int v : this.values) {\n"
                + "            sum += v;\n"
                + "        }\n"
                + "        return sum;\n"
                + "    }\n"
                + "}\n";
        final Map<Object, StopAnalysis.Info> infos = StopAnalysis.analyze(Arrays.asList(
                new StopAnalysis.Input("extract", 0, "/p/Statistics.java", oldText, newText,
                        changes(change(9, 10, 9, 13), change(13, 14, 16, 18)), false)));
        assertEquals(Arrays.asList("Extract method: Statistics.mean() → Statistics.sum()"),
                infos.get("extract").getRefactorings());
        // the call of the extracted method is a change of its own
        assertFalse(infos.get("extract").isRefactoringOnly());
    }

    @Test
    public void testTestsAreLinkedToTheProductionCode() {
        final Map<Object, StopAnalysis.Info> infos = analyze();
        assertTrue(infos.get("test").isTest());
        assertFalse(infos.get("rename").isTest());
        assertEquals("[Calculator -> rename, Calculator -> sum]",
                infos.get("test").getTested().toString());
        assertEquals("[CalculatorTest -> test]", infos.get("sum").getTests().toString());
        // Main does not declare a type the test uses
        assertTrue(infos.get("newCall").getTests().isEmpty());
    }

    @Test
    public void testTestNames() {
        assertEquals("Calculator", StopAnalysis.testedName("CalculatorTest"));
        assertEquals("Calculator", StopAnalysis.testedName("CalculatorTests"));
        assertEquals("Calculator", StopAnalysis.testedName("TestCalculator"));
        assertNull(StopAnalysis.testedName("Testable"));
        assertNull(StopAnalysis.testedName("Calculator"));
        assertTrue(StopAnalysis.looksLikeTest("C:\\p\\src\\test\\java\\demo\\Foo.java"));
        assertTrue(StopAnalysis.looksLikeTest("/p/src/main/java/demo/FooTest.java"));
        assertFalse(StopAnalysis.looksLikeTest("/p/src/main/java/demo/Contest.java"));
        assertEquals("sum", StopAnalysis.simpleName("Calculator.sum(java.util.List<Integer>)"));
        assertEquals("Calculator", StopAnalysis.simpleName("demo.Calculator"));
    }

}
