package de.setsoftware.reviewtool.intellij;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.setsoftware.reviewtool.ordering.HierarchyExplicitness;
import de.setsoftware.reviewtool.ordering.InSameFileRelation;
import de.setsoftware.reviewtool.ordering.InSameSourceFolderRelation;
import de.setsoftware.reviewtool.ordering.InSameSystemTestRelation;
import de.setsoftware.reviewtool.ordering.MethodCallRelation;
import de.setsoftware.reviewtool.ordering.MethodOverrideRelation;
import de.setsoftware.reviewtool.ordering.RelationMatcher;
import de.setsoftware.reviewtool.ordering.TokenSimilarityRelation;
import de.setsoftware.reviewtool.ordering.XsdBeforeRestRelation;

/**
 * The configuration of the relation types used to group and order the stops within a tour, the
 * IntelliJ counterpart of the Eclipse "RelationMatcherPreferences": which relation types are
 * active, in which priority order, and whether a match creates a nesting level in the tour tree.
 * It is stored as a string like {@code "SAME_FILE:ONLY_NONTRIVIAL;SOURCEFOLDER:ALWAYS"} (the
 * active types in priority order).
 */
final class StopOrderingSettings {

    /**
     * The known relation types. The order of the constants is the default priority order.
     */
    enum RelationType {
        SAME_FILE("In same file", HierarchyExplicitness.ALWAYS, HierarchyExplicitness.ONLY_NONTRIVIAL) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new InSameFileRelation(explicitness);
            }
        },
        SOURCEFOLDER("Source folder (test vs. src)", HierarchyExplicitness.ALWAYS, HierarchyExplicitness.ALWAYS) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new InSameSourceFolderRelation(explicitness);
            }
        },
        SYSTEMTEST("System test folder", HierarchyExplicitness.ALWAYS, HierarchyExplicitness.ALWAYS) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new InSameSystemTestRelation(explicitness);
            }
        },
        OVERRIDE("Method overriding", HierarchyExplicitness.ALWAYS, HierarchyExplicitness.ONLY_NONTRIVIAL) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new MethodOverrideRelation(explicitness);
            }
        },
        METHOD_CALL("Method calls", HierarchyExplicitness.ALWAYS, HierarchyExplicitness.NONE) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new MethodCallRelation(explicitness);
            }
        },
        SIMILARITY("Similar content", HierarchyExplicitness.NONE, HierarchyExplicitness.NONE) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new TokenSimilarityRelation();
            }
        },
        XSD_FIRST("XSD files first", HierarchyExplicitness.NONE, HierarchyExplicitness.NONE) {
            @Override
            RelationMatcher create(HierarchyExplicitness explicitness) {
                return new XsdBeforeRestRelation(explicitness);
            }
        };

        private final String description;
        private final HierarchyExplicitness maximumExplicitness;
        private final HierarchyExplicitness defaultExplicitness;

        RelationType(String description, HierarchyExplicitness maximumExplicitness,
                HierarchyExplicitness defaultExplicitness) {
            this.description = description;
            this.maximumExplicitness = maximumExplicitness;
            this.defaultExplicitness = defaultExplicitness;
        }

        abstract RelationMatcher create(HierarchyExplicitness explicitness);

        String getDescription() {
            return this.description;
        }

        HierarchyExplicitness getDefaultExplicitness() {
            return this.defaultExplicitness;
        }

        /**
         * The explicitness values that make sense for this type.
         */
        List<HierarchyExplicitness> getPossibleExplicitness() {
            final List<HierarchyExplicitness> ret = new ArrayList<>();
            for (final HierarchyExplicitness e : HierarchyExplicitness.values()) {
                if (e.ordinal() <= this.maximumExplicitness.ordinal()) {
                    ret.add(e);
                }
            }
            return ret;
        }

        HierarchyExplicitness limit(HierarchyExplicitness e) {
            return e.ordinal() <= this.maximumExplicitness.ordinal() ? e : this.maximumExplicitness;
        }
    }

    /**
     * An active relation type with its explicitness.
     */
    static final class Entry {
        private final RelationType type;
        private final HierarchyExplicitness explicitness;

        Entry(RelationType type, HierarchyExplicitness explicitness) {
            this.type = type;
            this.explicitness = type.limit(explicitness);
        }

        RelationType getType() {
            return this.type;
        }

        HierarchyExplicitness getExplicitness() {
            return this.explicitness;
        }
    }

    private StopOrderingSettings() {
    }

    static String describe(HierarchyExplicitness e) {
        switch (e) {
        case ALWAYS:
            return "always show in hierarchy";
        case ONLY_NONTRIVIAL:
            return "show in hierarchy if multiple children";
        case NONE:
        default:
            return "don't show in hierarchy";
        }
    }

    /**
     * The default configuration: all types active, in the default order and explicitness (the same
     * defaults the Eclipse UI uses).
     */
    static List<Entry> defaults() {
        final List<Entry> ret = new ArrayList<>();
        for (final RelationType t : RelationType.values()) {
            ret.add(new Entry(t, t.defaultExplicitness));
        }
        return ret;
    }

    /**
     * Parses the stored configuration. An empty or invalid configuration results in the defaults,
     * unknown parts are ignored.
     */
    static List<Entry> parse(String serialized) {
        if (serialized == null || serialized.trim().isEmpty()) {
            return defaults();
        }
        final List<Entry> ret = new ArrayList<>();
        final Set<RelationType> seen = EnumSet.noneOf(RelationType.class);
        for (final String part : serialized.split(";")) {
            final String[] typeAndExplicitness = part.trim().split(":");
            try {
                final RelationType type = RelationType.valueOf(typeAndExplicitness[0].trim());
                final HierarchyExplicitness explicitness = typeAndExplicitness.length > 1
                        ? HierarchyExplicitness.valueOf(typeAndExplicitness[1].trim())
                        : type.defaultExplicitness;
                if (seen.add(type)) {
                    ret.add(new Entry(type, explicitness));
                }
            } catch (final IllegalArgumentException e) {
                // ignore unknown types (e.g. from a newer version)
            }
        }
        return ret.isEmpty() && !"NONE".equals(serialized.trim()) ? defaults() : ret;
    }

    /**
     * Serializes the active types in their priority order. If no type is active, "NONE" is stored
     * (an empty string stands for the defaults).
     */
    static String serialize(List<Entry> entries) {
        if (entries.isEmpty()) {
            return "NONE";
        }
        final StringBuilder sb = new StringBuilder();
        for (final Entry e : entries) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.type.name()).append(':').append(e.explicitness.name());
        }
        return sb.toString();
    }

    /**
     * Returns the canonical form of the given configuration (so that "defaults" and the explicit
     * default configuration compare as equal).
     */
    static String normalize(String serialized) {
        return serialize(parse(serialized));
    }

    /**
     * Creates the relation matchers for the stored configuration.
     */
    static List<RelationMatcher> createMatchers(String serialized) {
        final List<RelationMatcher> ret = new ArrayList<>();
        for (final Entry e : parse(serialized)) {
            ret.add(e.type.create(e.explicitness));
        }
        return ret;
    }

}
