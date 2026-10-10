package de.setsoftware.reviewtool.intellij;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Icon;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import de.setsoftware.reviewtool.base.IMultimap;
import de.setsoftware.reviewtool.base.Multiset;
import de.setsoftware.reviewtool.model.api.IClassification;
import de.setsoftware.reviewtool.model.api.IFragment;
import de.setsoftware.reviewtool.model.api.IHunk;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.changestructure.Stop;
import de.setsoftware.reviewtool.model.changestructure.Tour;
import de.setsoftware.reviewtool.model.changestructure.TourElement;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatDataForStop;
import de.setsoftware.reviewtool.model.viewtracking.ViewStatistics;

/**
 * Creates the graphical icons for the stops and tours in the "Tours" tab, a port of the icons of the
 * Eclipse "Review content" view: the shape is derived from the stop's classification, its source
 * folder, the size of its history and the size of the changed fragment (see {@link StopIconGrammar}),
 * so similar stops look similar. The color shows how much of the stop has been viewed: the outline
 * the maximum and the filling the average view ratio, from yellow (just started) to green (fully
 * viewed). Unvisited stops are drawn in the text color, irrelevant ones in gray, stops marked as
 * checked get a green check mark and the active tour a red dot.
 */
final class StopIcons {

    private static final int SIZE = 16;

    private static final Color[] VIEW_COLORS = {
        new Color(255, 235, 0),
        new Color(223, 235, 0),
        new Color(191, 235, 0),
        new Color(159, 235, 0),
        new Color(127, 235, 0),
        new Color(95, 235, 0),
        new Color(63, 235, 0),
        new Color(32, 235, 0),
        new Color(0, 235, 0),
    };

    private static final JBColor IRRELEVANT_COLOR = new JBColor(new Color(170, 170, 170), new Color(110, 110, 110));
    private static final JBColor NOT_VIEWED_COLOR = new JBColor(new Color(10, 10, 10), new Color(200, 200, 200));
    private static final JBColor CHECK_COLOR = new JBColor(new Color(0, 170, 0), new Color(80, 200, 80));
    private static final Color ACTIVE_TOUR_COLOR = new Color(230, 0, 0);

    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    private final ViewStatistics statistics;
    private final int longEnoughCount;
    private final Set<? extends IClassification> irrelevantCategories;

    /**
     * Creates the icon factory for the current view statistics and irrelevant categories.
     */
    StopIcons(ViewStatistics statistics, int longEnoughCount, Set<? extends IClassification> irrelevantCategories) {
        this.statistics = statistics;
        this.longEnoughCount = longEnoughCount;
        this.irrelevantCategories = irrelevantCategories;
    }

    /**
     * Returns the icon for the given stop.
     */
    Icon forStop(Stop stop) {
        if (this.statistics.isMarkedAsChecked(stop)) {
            return checkMark();
        }
        final ViewStatDataForStop ratio = this.statistics.determineViewRatio(stop, this.longEnoughCount);
        return image(shape(stop), ratio.isNotViewedAtAll(), ratio.getMaxRatio(), ratio.getAverageRatio(),
                stop.isIrrelevantForReview(this.irrelevantCategories));
    }

    /**
     * Returns the icon for the given tour: the most common shape of its (relevant) stops, colored by
     * the aggregated view ratio, a check mark if all stops are checked, a red dot for the active tour.
     */
    Icon forTour(Tour tour, boolean active) {
        if (active) {
            return activeTourDot();
        }
        boolean allIrrelevant = true;
        boolean allNotViewedAtAll = true;
        boolean allChecked = true;
        double maxRatio = 0.0;
        double sumRatio = 0.0;
        int count = 0;
        final Multiset<StopIconGrammar> shapes = new Multiset<>();
        final Multiset<StopIconGrammar> relevantShapes = new Multiset<>();
        for (final Stop stop : tour.getStops()) {
            final boolean irrelevant = stop.isIrrelevantForReview(this.irrelevantCategories);
            if (this.statistics.isMarkedAsChecked(stop)) {
                maxRatio = 1.0;
            } else {
                allChecked = false;
            }
            final ViewStatDataForStop ratio = this.statistics.determineViewRatio(stop, this.longEnoughCount);
            allNotViewedAtAll &= ratio.isNotViewedAtAll();
            maxRatio = Math.max(maxRatio, ratio.getMaxRatio());
            sumRatio += ratio.getAverageRatio();
            count++;
            allIrrelevant &= irrelevant;
            final StopIconGrammar shape = shape(stop);
            shapes.add(shape);
            if (!irrelevant) {
                relevantShapes.add(shape);
            }
        }
        if (count == 0) {
            return image(null, true, 0.0, 0.0, false);
        }
        if (allChecked) {
            return checkMark();
        }
        final StopIconGrammar shape = relevantShapes.isEmpty()
                ? shapes.getMostCommonItem() : relevantShapes.getMostCommonItem();
        return image(shape, allNotViewedAtAll, maxRatio, sumRatio / count, allIrrelevant);
    }

    /**
     * The shape for a stop, determined like in the Eclipse plugin.
     */
    static StopIconGrammar shape(Stop stop) {
        return StopIconGrammar.create(
                classificationToShapeId(stop),
                srcdirToNumber(stop) * 4 + historyToNumber(stop),
                curSizeToNumber(stop));
    }

    private static int classificationToShapeId(TourElement e) {
        int ret = 0;
        for (final IClassification cl : e.getClassification()) {
            ret += cl.getNumber();
        }
        return ret;
    }

    private static int historyToNumber(Stop stop) {
        int sum = 0;
        final IMultimap<IRevisionedFile, IHunk> history = stop.getHunks();
        for (final IRevisionedFile file : history.keySet()) {
            for (final IHunk h : history.get(file)) {
                sum += h.getSource().getNumberOfLines();
            }
        }
        return Math.min(sum, 3);
    }

    private static int srcdirToNumber(Stop stop) {
        final String filename = stop.getMostRecentFile().getPath();
        if (filename.contains("/src/")) {
            return 2;
        } else if (filename.contains("/test/")) {
            return 1;
        } else {
            return 0;
        }
    }

    private static int curSizeToNumber(Stop stop) {
        final IFragment fragment = stop.getMostRecentFragment();
        if (fragment == null) {
            return 0;
        }
        return Math.min(11, Integer.numberOfTrailingZeros(Integer.highestOneBit(fragment.getNumberOfLines())) + 1);
    }

    private static Icon image(StopIconGrammar shape, boolean notViewedAtAll, double maxRatio, double averageRatio,
            boolean irrelevant) {
        final Color line;
        final Color fill;
        if (shape == null || notViewedAtAll) {
            line = irrelevant ? IRRELEVANT_COLOR : NOT_VIEWED_COLOR;
            fill = line;
        } else {
            line = VIEW_COLORS[toColorIndex(maxRatio)];
            fill = VIEW_COLORS[toColorIndex(averageRatio)];
        }
        final String key = shape + "|" + line + "|" + fill + "|" + (line instanceof JBColor);
        return CACHE.computeIfAbsent(key, (k) -> new ShapeIcon(shape, line, fill));
    }

    private static int toColorIndex(double ratio) {
        final int index = (int) (ratio * (VIEW_COLORS.length - 1));
        return Math.max(0, Math.min(VIEW_COLORS.length - 1, index));
    }

    static Icon checkMark() {
        return CACHE.computeIfAbsent("check", (k) -> new PaintedIcon() {
            @Override
            void paint(Graphics2D g) {
                g.setColor(CHECK_COLOR);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(3, 9, 6, 12);
                g.drawLine(6, 12, 13, 3);
            }
        });
    }

    static Icon activeTourDot() {
        return CACHE.computeIfAbsent("activeTour", (k) -> new PaintedIcon() {
            @Override
            void paint(Graphics2D g) {
                g.setColor(ACTIVE_TOUR_COLOR);
                g.fillOval(4, 4, 8, 8);
            }
        });
    }

    /**
     * An icon in tree icon size that is painted in a 16x16 coordinate system (scaled for HiDPI).
     */
    private abstract static class PaintedIcon implements Icon {

        abstract void paint(Graphics2D g);

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                final double scale = (double) JBUI.scale(SIZE) / SIZE;
                g2.scale(scale, scale);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.5f));
                this.paint(g2);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return JBUI.scale(SIZE);
        }

        @Override
        public int getIconHeight() {
            return JBUI.scale(SIZE);
        }
    }

    /**
     * Icon for a grammar based shape (or a simple rectangle if there is no shape).
     */
    private static final class ShapeIcon extends PaintedIcon {
        private final StopIconGrammar shape;
        private final Color line;
        private final Color fill;

        ShapeIcon(StopIconGrammar shape, Color line, Color fill) {
            this.shape = shape;
            this.line = line;
            this.fill = fill;
        }

        @Override
        void paint(Graphics2D g) {
            if (this.shape == null) {
                g.setColor(this.line);
                g.fillRect(4, 3, 8, 10);
                return;
            }
            g.translate(1, 1);
            this.shape.paint(g, SIZE - 2, SIZE - 2, this.line, this.fill);
        }
    }

}
