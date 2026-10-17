package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link SecurityHints} and {@link ReviewChecklist}.
 */
public class ReviewHintsTest {

    private static List<String> topics(String code) {
        final List<String> ret = new ArrayList<>();
        for (final SecurityHints.Hint hint : SecurityHints.find(code)) {
            ret.add(hint.getTopic());
        }
        return ret;
    }

    @Test
    public void testSqlConcatenation() {
        assertEquals(Arrays.asList("SQL"),
                topics("stmt.executeQuery(\"SELECT * FROM users WHERE name = '\" + name + \"'\");"));
        assertEquals(Arrays.asList("SQL"), topics("final String sql = \"DELETE FROM t WHERE id = \" + id;"));
        // bind parameters are fine
        assertEquals(Collections.emptyList(),
                topics("conn.prepareStatement(\"SELECT * FROM users WHERE name = ?\");"));
    }

    @Test
    public void testOtherRiskyApis() {
        assertEquals(Arrays.asList("Command execution"), topics("new ProcessBuilder(\"git\", arg).start();"));
        assertEquals(Arrays.asList("Input validation"), topics("final String id = request.getParameter(\"id\");"));
        assertEquals(Arrays.asList("Input validation"), topics("public User get(@PathVariable String id) {"));
        assertEquals(Arrays.asList("File path"), topics("new File(baseDir + \"/\" + name)"));
        assertEquals(Arrays.asList("Deserialization"),
                topics("final Object o = new ObjectInputStream(in).readObject();"));
        assertEquals(Arrays.asList("XML parser"), topics("DocumentBuilderFactory.newInstance().newDocumentBuilder()"));
        assertEquals(Arrays.asList("Cryptography"), topics("MessageDigest.getInstance(\"MD5\")"));
        assertEquals(Arrays.asList("Secret in the code"),
                topics("private static final String PASSWORD = \"geheim123\";"));
    }

    @Test
    public void testHarmlessCodeHasNoHints() {
        assertEquals(Collections.emptyList(), topics("return (double) this.sum() / this.values.size();"));
        assertEquals(Collections.emptyList(), topics("final File file = new File(\"config.xml\");"));
        // the parser is configured
        assertEquals(Collections.emptyList(), topics(
                "final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();\n"
                + "f.setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true);"));
        assertEquals(Collections.emptyList(), topics("MessageDigest.getInstance(\"SHA-256\")"));
        assertEquals(Collections.emptyList(), topics("final String password = readPassword();"));
    }

    @Test
    public void testChecklist() {
        final ReviewChecklist checklist = ReviewChecklist.parse("# Review checklist\n"
                + "Some text that is ignored.\n"
                + "## **/persistence/**, *.sql\n"
                + "- Are all SQL statements parameterized?\n"
                + "- [ ] Is every transaction closed?\n"
                + "## `*.java`\n"
                + "* Are the new public methods documented?\n"
                + "## src/main/**\n"
                + "- Are all SQL statements parameterized?\n");
        assertEquals(Arrays.asList("Are all SQL statements parameterized?", "Is every transaction closed?",
                "Are the new public methods documented?"),
                checklist.questionsFor("src/main/java/demo/persistence/UserDao.java"));
        // a pattern starting with "**/" also matches directly in the project directory
        assertEquals(Arrays.asList("Are all SQL statements parameterized?", "Is every transaction closed?"),
                checklist.questionsFor("persistence/schema.txt"));
        assertEquals(Arrays.asList("Are all SQL statements parameterized?", "Is every transaction closed?"),
                checklist.questionsFor("db\\migration.sql"));
        assertEquals(Arrays.asList("Are the new public methods documented?"),
                checklist.questionsFor("src/test/java/demo/FooTest.java"));
        assertTrue(checklist.questionsFor("README.md").isEmpty());
        assertTrue(ReviewChecklist.parse("# only a heading\n").isEmpty());
    }

}
