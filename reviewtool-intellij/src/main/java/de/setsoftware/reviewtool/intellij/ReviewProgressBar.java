package de.setsoftware.reviewtool.intellij;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

/**
 * Shows the progress of the review as a segmented bar (irrelevant / visited / partly visited /
 * unvisited stops) together with a textual note, the IntelliJ counterpart of the Eclipse
 * "ProgressChart".
 */
final class ReviewProgressBar extends JComponent {

    private static final long serialVersionUID = 1L;

    static final JBColor IRRELEVANT_COLOR = new JBColor(new Color(0xB4B4B4), new Color(0x5C5C5C));
    static final JBColor VISITED_COLOR = new JBColor(new Color(0x59A869), new Color(0x499C54));
    static final JBColor PARTLY_VISITED_COLOR = new JBColor(new Color(0xE5C04A), new Color(0xBE9117));
    static final JBColor UNVISITED_COLOR = new JBColor(new Color(0xDB5C5C), new Color(0xC75450));

    private static final int BAR_HEIGHT = 8;

    private int irrelevant;
    private int visited;
    private int partlyVisited;
    private int unvisited;
    private boolean hasData;

    ReviewProgressBar() {
        this.setOpaque(false);
    }

    /**
     * Updates the counts and repaints the bar.
     */
    void setCounts(int irrelevant, int visited, int partlyVisited, int unvisited) {
        this.irrelevant = irrelevant;
        this.visited = visited;
        this.partlyVisited = partlyVisited;
        this.unvisited = unvisited;
        this.hasData = true;
        this.setToolTipText(String.format(
                "<html>%d visited or checked<br>%d partly visited<br>%d unvisited (relevant)<br>%d irrelevant</html>",
                visited, partlyVisited, unvisited, irrelevant));
        this.repaint();
    }

    /**
     * Clears the bar (no tours).
     */
    void clear() {
        this.hasData = false;
        this.setToolTipText(null);
        this.repaint();
    }

    String getSummaryText() {
        if (!this.hasData) {
            return "";
        }
        if (this.unvisited == 0 && this.partlyVisited == 0) {
            return "All relevant stops visited";
        }
        return this.unvisited + (this.unvisited == 1 ? " unvisited relevant stop left" : " unvisited relevant stops left")
                + (this.partlyVisited > 0 ? ", " + this.partlyVisited + " partly visited" : "");
    }

    @Override
    public Dimension getPreferredSize() {
        final int textHeight = this.getFontMetrics(this.getFont()).getHeight();
        return new Dimension(JBUI.scale(100), JBUI.scale(BAR_HEIGHT) + textHeight + JBUI.scale(6));
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!this.hasData) {
            return;
        }
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final int x = JBUI.scale(2);
            final int width = this.getWidth() - 2 * x;
            final int barHeight = JBUI.scale(BAR_HEIGHT);
            final int total = this.irrelevant + this.visited + this.partlyVisited + this.unvisited;
            int startX = x;
            if (total > 0) {
                startX = this.paintPart(g2, startX, x + width, barHeight, this.irrelevant, total, width, IRRELEVANT_COLOR);
                startX = this.paintPart(g2, startX, x + width, barHeight, this.visited, total, width, VISITED_COLOR);
                startX = this.paintPart(
                        g2, startX, x + width, barHeight, this.partlyVisited, total, width, PARTLY_VISITED_COLOR);
                this.paintPart(g2, startX, x + width, barHeight, this.unvisited, total, width, UNVISITED_COLOR);
            }
            g2.setColor(JBColor.border());
            g2.drawRect(x, JBUI.scale(2), width - 1, barHeight);

            g2.setColor(UIUtil.getLabelForeground());
            g2.setFont(this.getFont());
            final int textY = JBUI.scale(2) + barHeight + g2.getFontMetrics().getAscent() + JBUI.scale(2);
            g2.drawString(this.getSummaryText(), x, textY);
        } finally {
            g2.dispose();
        }
    }

    private int paintPart(Graphics2D g2, int startX, int maxX, int barHeight, int value, int total, int width,
            Color color) {
        if (value <= 0) {
            return startX;
        }
        final int partWidth = Math.max(1, (int) Math.round((double) value * width / total));
        final int endX = Math.min(maxX, startX + partWidth);
        g2.setColor(color);
        g2.fillRect(startX, JBUI.scale(2), endX - startX, barHeight);
        return endX;
    }

}
