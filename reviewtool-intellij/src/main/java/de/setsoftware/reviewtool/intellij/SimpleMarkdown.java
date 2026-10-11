package de.setsoftware.reviewtool.intellij;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the Markdown of the AI answers to simple HTML for a Swing text pane: headings, bulleted and
 * numbered lists, code blocks, inline code, bold and italic text, links (shown as text) and
 * paragraphs. Everything else is shown as text; HTML in the answer is escaped, never interpreted.
 */
final class SimpleMarkdown {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?!\\s)([^*]+?)\\*(?![\\w*])");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

    private SimpleMarkdown() {
    }

    static String toHtml(String markdown) {
        final StringBuilder html = new StringBuilder();
        final StringBuilder paragraph = new StringBuilder();
        String openList = null;
        boolean inCode = false;
        for (final String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                flushParagraph(html, paragraph);
                openList = closeList(html, openList);
                html.append(inCode ? "</pre>" : "<pre>");
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                html.append(escape(line)).append('\n');
                continue;
            }
            final Matcher heading = HEADING.matcher(line);
            final Matcher bullet = BULLET.matcher(line);
            final Matcher numbered = NUMBERED.matcher(line);
            if (heading.matches()) {
                flushParagraph(html, paragraph);
                openList = closeList(html, openList);
                // h1/h2 are too big in the narrow tool window
                final int level = Math.min(6, heading.group(1).length() + 2);
                html.append("<h").append(level).append('>').append(inline(heading.group(2)))
                        .append("</h").append(level).append('>');
            } else if (bullet.matches() || numbered.matches()) {
                flushParagraph(html, paragraph);
                final String type = bullet.matches() ? "ul" : "ol";
                if (!type.equals(openList)) {
                    closeList(html, openList);
                    html.append('<').append(type).append('>');
                    openList = type;
                }
                html.append("<li>").append(inline((bullet.matches() ? bullet : numbered).group(1))).append("</li>");
            } else if (line.trim().isEmpty()) {
                flushParagraph(html, paragraph);
                openList = closeList(html, openList);
            } else {
                if (openList != null && line.startsWith("  ")) {
                    // continuation of a list item
                    html.insert(html.length() - "</li>".length(), " " + inline(line.trim()));
                    continue;
                }
                openList = closeList(html, openList);
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line.trim());
            }
        }
        flushParagraph(html, paragraph);
        closeList(html, openList);
        if (inCode) {
            html.append("</pre>");
        }
        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(inline(paragraph.toString())).append("</p>");
            paragraph.setLength(0);
        }
    }

    private static String closeList(StringBuilder html, String openList) {
        if (openList != null) {
            html.append("</").append(openList).append('>');
        }
        return null;
    }

    /**
     * Converts the inline formatting. Code spans are protected from the other replacements.
     */
    private static String inline(String text) {
        final StringBuilder result = new StringBuilder();
        final Matcher code = CODE.matcher(text);
        int last = 0;
        while (code.find()) {
            result.append(formatting(text.substring(last, code.start())));
            result.append("<code>").append(escape(code.group(1))).append("</code>");
            last = code.end();
        }
        result.append(formatting(text.substring(last)));
        return result.toString();
    }

    private static String formatting(String text) {
        String s = escape(text);
        s = LINK.matcher(s).replaceAll("$1");
        s = BOLD.matcher(s).replaceAll("<b>$1$2</b>");
        s = ITALIC.matcher(s).replaceAll("<i>$1</i>");
        return s;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

}
