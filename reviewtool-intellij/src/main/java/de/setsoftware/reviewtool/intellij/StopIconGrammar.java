package de.setsoftware.reviewtool.intellij;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Objects;

/**
 * Creates various icon shapes based on a very simple generational grammar, a port of the Eclipse
 * "IconGrammar" to AWT: a basic structure (a grid with two kinds of cells) is filled with two leaf
 * shapes. Different stops get different shapes, so that similar stops can be recognized at a glance.
 */
final class StopIconGrammar {

    /**
     * The basic structure of the icon (0 = empty, 1 = first leaf shape, 2 = second leaf shape).
     */
    private enum BasicShape {
        SHAPE1(new int[][] {{0, 1}, {2, 0}}),
        SHAPE2(new int[][] {{1, 0}, {0, 2}}),
        SHAPE3(new int[][] {{0, 1, 0}, {0, 2, 0}, {0, 1, 0}}),
        SHAPE4(new int[][] {{0, 1, 0}, {1, 2, 1}, {0, 1, 0}}),
        SHAPE5(new int[][] {{0, 0, 0}, {1, 2, 1}, {0, 0, 0}}),
        SHAPE6(new int[][] {{1, 0, 2}, {1, 0, 2}, {1, 0, 2}}),
        SHAPE7(new int[][] {{1, 1, 1}, {0, 0, 0}, {2, 2, 2}}),
        SHAPE8(new int[][] {{0, 0, 1}, {2, 0, 0}, {0, 0, 1}}),
        SHAPE9(new int[][] {{1, 0, 0}, {0, 0, 2}, {1, 0, 0}}),
        SHAPE10(new int[][] {{1, 0, 1}, {0, 2, 0}, {1, 0, 1}}),
        SHAPE11(new int[][] {{1, 0, 0}, {1, 0, 0}, {0, 0, 2}}),
        SHAPE12(new int[][] {{0, 0, 1}, {0, 0, 1}, {2, 0, 0}});

        private final int[][] fields;

        BasicShape(int[][] fields) {
            this.fields = fields;
        }

        void paint(Graphics2D g, int height, int width, LeafShape leaf1, LeafShape leaf2, Color line, Color fill) {
            for (int i = 0; i < this.fields.length; i++) {
                final int lower = height * i / this.fields.length;
                final int upper = height * (i + 1) / this.fields.length;
                for (int j = 0; j < this.fields[i].length; j++) {
                    final int left = width * j / this.fields[i].length;
                    final int right = width * (j + 1) / this.fields[i].length;
                    if (this.fields[i][j] == 1) {
                        leaf1.paint(g, line, fill, lower, upper, left, right);
                    } else if (this.fields[i][j] == 2) {
                        leaf2.paint(g, line, fill, lower, upper, left, right);
                    }
                }
            }
        }
    }

    /**
     * The shapes that are filled into the cells of the basic structure. Lines and outlines use the
     * current color of the graphics, filled shapes the fill color.
     */
    private enum LeafShape {
        LINE1 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine(left, upper, right, lower);
            }
        },
        LINE2 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine(left, lower, right, upper);
            }
        },
        LINE3 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine((left + right) / 2, upper, (left + right) / 2, lower);
            }
        },
        LINE4 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine(left, (lower + upper) / 2, right, (lower + upper) / 2);
            }
        },
        TWOLINE1 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine(left, upper, right, lower);
                g.drawLine(left, lower, right, upper);
            }
        },
        TWOLINE2 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine((left + right) / 2, upper, (left + right) / 2, lower);
                g.drawLine(left, (lower + upper) / 2, right, (lower + upper) / 2);
            }
        },
        TWOLINE3 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine(left, upper, left, lower);
                g.drawLine(right, upper, right, lower);
            }
        },
        TWOLINE4 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawLine(left, lower, right, lower);
                g.drawLine(left, upper, right, upper);
            }
        },
        BOX1 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawRect(left, lower, right - left, upper - lower);
            }
        },
        BOX2 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(fill);
                g.fillRect(left, lower, right - left, upper - lower);
            }
        },
        BOX3 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(line);
                g.drawPolygon(diamondXs(left, right), diamondYs(lower, upper), 4);
            }
        },
        BOX4 {
            @Override
            void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right) {
                g.setColor(fill);
                g.fillPolygon(diamondXs(left, right), diamondYs(lower, upper), 4);
            }
        };

        abstract void paint(Graphics2D g, Color line, Color fill, int lower, int upper, int left, int right);

        private static int[] diamondXs(int left, int right) {
            return new int[] {(left + right) / 2, right, (left + right) / 2, left};
        }

        private static int[] diamondYs(int lower, int upper) {
            return new int[] {upper, (lower + upper) / 2, lower, (lower + upper) / 2};
        }
    }

    private final BasicShape basicShape;
    private final LeafShape leaf1;
    private final LeafShape leaf2;

    private StopIconGrammar(BasicShape basicShape, LeafShape leaf1, LeafShape leaf2) {
        this.basicShape = basicShape;
        this.leaf1 = leaf1;
        this.leaf2 = leaf2;
    }

    /**
     * Creates the icon shape for the given (arbitrary, non-negative) numbers.
     */
    static StopIconGrammar create(int basic, int leaf1, int leaf2) {
        return new StopIconGrammar(
                val(BasicShape.values(), basic),
                val(LeafShape.values(), leaf1),
                val(LeafShape.values(), leaf2));
    }

    private static <T> T val(T[] arr, int i) {
        return arr[Math.abs(i) % arr.length];
    }

    /**
     * Paints the shape in the given size: lines and outlines in the line color, filled shapes in the
     * fill color (like foreground and background of the SWT graphics context in the Eclipse version).
     */
    void paint(Graphics2D g, int height, int width, Color line, Color fill) {
        this.basicShape.paint(g, height, width, this.leaf1, this.leaf2, line, fill);
    }

    @Override
    public String toString() {
        return this.basicShape + "," + this.leaf1 + "," + this.leaf2;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.basicShape, this.leaf1, this.leaf2);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof StopIconGrammar)) {
            return false;
        }
        final StopIconGrammar g = (StopIconGrammar) o;
        return this.basicShape == g.basicShape && this.leaf1 == g.leaf1 && this.leaf2 == g.leaf2;
    }

}
