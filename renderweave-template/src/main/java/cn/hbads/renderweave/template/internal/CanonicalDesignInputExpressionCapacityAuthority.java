package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class CanonicalDesignInputExpressionCapacityAuthority
        implements DesignInputExpressionCapacityAuthority {

    static final CanonicalDesignInputExpressionCapacityAuthority INSTANCE =
            new CanonicalDesignInputExpressionCapacityAuthority();

    private static final Pattern CANONICAL_INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Pattern CANONICAL_DECIMAL =
            Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Accepted ACCEPTED = new Accepted();

    private static final Terminal RENDER_INPUT_ENCODING_TERMINAL = terminal(
            "RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED",
            "RENDER_INPUT_ADMISSION",
            "INPUT_ADMISSION",
            "ZERO_EVALUATION_DOCUMENT_OUTPUT",
            "capabilityStates=0",
            "evaluations=0",
            "renderDocuments=0",
            "engineCommands=0",
            "renderOutputs=0"
    );
    private static final Terminal RENDER_INPUT_LIMIT_TERMINAL = terminal(
            "RENDER_INPUT_LIMIT_EXCEEDED",
            "RENDER_INPUT_ADMISSION",
            "INPUT_ADMISSION",
            "ZERO_EVALUATION_DOCUMENT_OUTPUT",
            "capabilityStates=0",
            "evaluations=0",
            "renderDocuments=0",
            "engineCommands=0",
            "renderOutputs=0"
    );
    private static final Terminal PROBLEM_LIMIT_TERMINAL = terminal(
            "PROBLEM_LIMIT_REACHED",
            "BOUNDED_PROBLEM_COLLECTION",
            "ORIGINATING_STAGE",
            "BOUNDED_PROBLEM_PREFIX_ONLY",
            "boundedProblemPrefix=1",
            "problemLimitMarkers=1"
    );
    private static final Terminal DESIGN_PARSE_TERMINAL = templateTerminal(
            "DESIGN_DSL_LIMIT_EXCEEDED",
            "DESIGN_PARSE"
    );
    private static final Terminal DESIGN_CANONICAL_TERMINAL = templateTerminal(
            "DESIGN_DSL_LIMIT_EXCEEDED",
            "DESIGN_CANONICAL_COUNT"
    );
    private static final Terminal DESIGN_SEMANTIC_TERMINAL = templateTerminal(
            "DESIGN_DSL_LIMIT_EXCEEDED",
            "DESIGN_SEMANTIC_VALIDATION"
    );
    private static final Terminal EXPRESSION_TERMINAL = templateTerminal(
            "EXPRESSION_LIMIT_EXCEEDED",
            "EXPRESSION_PARSE_AND_STATIC_ANALYSIS"
    );
    private static final Terminal GEOMETRY_TERMINAL = templateTerminal(
            "DESIGN_PROPERTY_CONSTRAINT_INVALID",
            "DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION"
    );

    private static final Map<String, Rule> RULES = rules();

    private CanonicalDesignInputExpressionCapacityAuthority() {
    }

    @Override
    public Decision evaluate(Observation observation) {
        if (observation == null) {
            return new Invalid(InvalidReason.INVALID_OBSERVED_VALUE);
        }
        Rule rule = RULES.get(observation.limitId());
        if (rule == null) {
            return new Invalid(InvalidReason.UNKNOWN_LIMIT);
        }
        Boolean accepted = rule.accepts(observation.observedValue());
        if (accepted == null) {
            return new Invalid(InvalidReason.INVALID_OBSERVED_VALUE);
        }
        return accepted ? ACCEPTED : new Rejected(rule.terminal());
    }

    private static Map<String, Rule> rules() {
        Map<String, Rule> rules = new LinkedHashMap<>();

        enumExact(rules, "renderInput.contentEncoding", "identity", RENDER_INPUT_ENCODING_TERMINAL);
        maxInteger(rules, "renderInput.utf8Bytes", "8388608", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.jsonDepth", "32", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.objectMembers", "1024", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.arrayItems", "10000", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.totalValuesAndContainers", "250000", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.stringUtf8Bytes", "1048576", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.numberTokenBytes", "256", RENDER_INPUT_LIMIT_TERMINAL);
        maxInteger(rules, "renderInput.customValueEntries", "256", RENDER_INPUT_LIMIT_TERMINAL);

        maxInteger(rules, "problems.itemsIncludingLimitMarker", "200", PROBLEM_LIMIT_TERMINAL);
        exactInteger(rules, "problems.ordinaryItemsWhenTruncated", "199", PROBLEM_LIMIT_TERMINAL);
        maxInteger(rules, "problems.canonicalBytesPerItem", "4096", PROBLEM_LIMIT_TERMINAL);
        maxInteger(rules, "problems.canonicalBytesTotal", "262144", PROBLEM_LIMIT_TERMINAL);
        exactInteger(rules, "problems.limitMarkerReservedBytes", "1024", PROBLEM_LIMIT_TERMINAL);

        maxInteger(rules, "designDslParser.rawUtf8Bytes", "16777216", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.canonicalBytes", "16777216", DESIGN_CANONICAL_TERMINAL);
        maxInteger(rules, "designDslParser.jsonDepth", "64", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.objectMembers", "1024", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.arrayItems", "100000", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.totalValuesAndContainers", "1000000", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.stringUtf8Bytes", "1048576", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.memberNameUtf8Bytes", "256", DESIGN_PARSE_TERMINAL);
        maxInteger(rules, "designDslParser.numberTokenBytes", "256", DESIGN_PARSE_TERMINAL);

        maxInteger(rules, "designDslSemantics.authoredNodes", "4096", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.authoredTreeDepth", "32", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.childrenPerContainer", "1024", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.definitions", "512", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.bindingsTotal", "4096", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.bindingsPerNode", "64", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.runsPerTextNode", "256", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.runsTotal", "4096", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.gridTracksPerAxis", "64", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.vectorEntriesPerNode", "10000", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.vectorEntriesTotal", "100000", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.fillsPerTemplateUse", "256", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.literalListItemsPerList", "4096", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.literalListItemsTotal", "16384", DESIGN_SEMANTIC_TERMINAL);
        maxInteger(rules, "designDslSemantics.authoredRunTextScalars", "1000000", DESIGN_SEMANTIC_TERMINAL);

        maxInteger(rules, "expression.sourceUtf8BytesPerExpression", "65536", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.sourceUtf8BytesTotal", "1048576", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.inputsPerExpression", "32", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.inputsTotal", "4096", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.mappingCasesPerDefinition", "256", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.mappingCasesTotal", "8192", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.astNodesPerExpression", "4096", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.astNodesTotal", "65536", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.definitionGraphEdges", "8192", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.definitionChainDepth", "64", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.admittedDecimalPrecisionDigits", "128", EXPRESSION_TERMINAL);
        minInteger(rules, "expression.admittedDecimalScaleMin", "-64", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.admittedDecimalScaleMax", "64", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.intermediateDecimalPrecisionDigits", "256", EXPRESSION_TERMINAL);
        minInteger(rules, "expression.intermediateDecimalScaleMin", "-128", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.intermediateDecimalScaleMax", "128", EXPRESSION_TERMINAL);
        maxInteger(rules, "expression.explicitRoundingScaleMax", "64", EXPRESSION_TERMINAL);

        minExclusiveDecimal(rules, "geometry.canvasTrimMmPerAxisExclusiveMin", "0", GEOMETRY_TERMINAL);
        maxDecimal(rules, "geometry.canvasTrimMmPerAxisMax", "1000", GEOMETRY_TERMINAL);
        minDecimal(rules, "geometry.bleedMmPerSideMin", "0", GEOMETRY_TERMINAL);
        maxDecimal(rules, "geometry.bleedMmPerSideMax", "100", GEOMETRY_TERMINAL);
        maxDecimal(rules, "geometry.authoredCoordinateOrLengthMmAbsoluteMax", "10000", GEOMETRY_TERMINAL);
        minExclusiveDecimal(rules, "geometry.fontSizePtExclusiveMin", "0", GEOMETRY_TERMINAL);
        maxDecimal(rules, "geometry.fontSizePtMax", "4096", GEOMETRY_TERMINAL);
        maxDecimal(rules, "geometry.transformScaleAbsoluteMax", "100", GEOMETRY_TERMINAL);
        minDecimal(rules, "geometry.rotationDegreesMin", "-360", GEOMETRY_TERMINAL);
        maxDecimal(rules, "geometry.rotationDegreesMax", "360", GEOMETRY_TERMINAL);

        if (rules.size() != 65) {
            throw new IllegalStateException("Design/Input/Expression capacity rule count drifted");
        }
        return Map.copyOf(rules);
    }

    private static void maxInteger(Map<String, Rule> rules, String id, String limit, Terminal terminal) {
        add(rules, id, new Rule(Encoding.INTEGER, Comparison.MAX_INCLUSIVE, limit, terminal));
    }

    private static void minInteger(Map<String, Rule> rules, String id, String limit, Terminal terminal) {
        add(rules, id, new Rule(Encoding.INTEGER, Comparison.MIN_INCLUSIVE, limit, terminal));
    }

    private static void exactInteger(Map<String, Rule> rules, String id, String limit, Terminal terminal) {
        add(rules, id, new Rule(Encoding.INTEGER, Comparison.EXACT, limit, terminal));
    }

    private static void maxDecimal(Map<String, Rule> rules, String id, String limit, Terminal terminal) {
        add(rules, id, new Rule(Encoding.DECIMAL, Comparison.MAX_INCLUSIVE, limit, terminal));
    }

    private static void minDecimal(Map<String, Rule> rules, String id, String limit, Terminal terminal) {
        add(rules, id, new Rule(Encoding.DECIMAL, Comparison.MIN_INCLUSIVE, limit, terminal));
    }

    private static void minExclusiveDecimal(
            Map<String, Rule> rules,
            String id,
            String limit,
            Terminal terminal
    ) {
        add(rules, id, new Rule(Encoding.DECIMAL, Comparison.MIN_EXCLUSIVE, limit, terminal));
    }

    private static void enumExact(Map<String, Rule> rules, String id, String limit, Terminal terminal) {
        add(rules, id, new Rule(Encoding.ENUM, Comparison.EXACT, limit, terminal));
    }

    private static void add(Map<String, Rule> rules, String id, Rule rule) {
        if (rules.put(id, rule) != null) {
            throw new IllegalStateException("Duplicate capacity rule: " + id);
        }
    }

    private static Terminal templateTerminal(String code, String contractStage) {
        return terminal(
                code,
                contractStage,
                "TEMPLATE_CLOSURE",
                "ZERO_WRITE_AND_DOWNSTREAM",
                "templateWrites=0",
                "assetWrites=0",
                "evaluationStarts=0",
                "renderDocuments=0",
                "renderOutputs=0"
        );
    }

    private static Terminal terminal(
            String code,
            String contractStage,
            String publicRenderStage,
            String zeroBoundary,
            String... downstreamEffects
    ) {
        return new Terminal(
                code,
                contractStage,
                publicRenderStage,
                zeroBoundary,
                List.of(downstreamEffects)
        );
    }

    private enum Encoding {
        INTEGER,
        DECIMAL,
        ENUM
    }

    private enum Comparison {
        MAX_INCLUSIVE,
        MIN_INCLUSIVE,
        MIN_EXCLUSIVE,
        EXACT
    }

    private record Rule(
            Encoding encoding,
            Comparison comparison,
            String limitValue,
            Terminal terminal
    ) {
        private Boolean accepts(String observedValue) {
            if (observedValue == null) {
                return null;
            }
            if (encoding == Encoding.ENUM) {
                return observedValue.isEmpty() ? null : observedValue.equals(limitValue);
            }
            Pattern pattern = encoding == Encoding.INTEGER ? CANONICAL_INTEGER : CANONICAL_DECIMAL;
            if (!pattern.matcher(observedValue).matches()) {
                return null;
            }
            int ordering;
            try {
                ordering = encoding == Encoding.INTEGER
                        ? Long.compare(Long.parseLong(observedValue), Long.parseLong(limitValue))
                        : compareCanonicalDecimal(observedValue, limitValue);
            } catch (NumberFormatException invalid) {
                return null;
            }
            return switch (comparison) {
                case MAX_INCLUSIVE -> ordering <= 0;
                case MIN_INCLUSIVE -> ordering >= 0;
                case MIN_EXCLUSIVE -> ordering > 0;
                case EXACT -> ordering == 0;
            };
        }

        private static int compareCanonicalDecimal(String left, String right) {
            int leftSign = decimalSign(left);
            int rightSign = decimalSign(right);
            if (leftSign != rightSign) {
                return Integer.compare(leftSign, rightSign);
            }
            if (leftSign == 0) {
                return 0;
            }
            int magnitude = compareDecimalMagnitude(left, right);
            return leftSign < 0 ? -magnitude : magnitude;
        }

        private static int decimalSign(String value) {
            boolean negative = value.charAt(0) == '-';
            for (int index = negative ? 1 : 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current != '.' && current != '0') {
                    return negative ? -1 : 1;
                }
            }
            return 0;
        }

        private static int compareDecimalMagnitude(String left, String right) {
            int leftStart = left.charAt(0) == '-' ? 1 : 0;
            int rightStart = right.charAt(0) == '-' ? 1 : 0;
            int leftPoint = decimalPoint(left, leftStart);
            int rightPoint = decimalPoint(right, rightStart);
            int leftIntegerLength = leftPoint - leftStart;
            int rightIntegerLength = rightPoint - rightStart;
            if (leftIntegerLength != rightIntegerLength) {
                return Integer.compare(leftIntegerLength, rightIntegerLength);
            }
            for (int index = 0; index < leftIntegerLength; index++) {
                int comparison = Character.compare(
                        left.charAt(leftStart + index),
                        right.charAt(rightStart + index)
                );
                if (comparison != 0) {
                    return comparison;
                }
            }
            int leftFractionStart = leftPoint == left.length() ? leftPoint : leftPoint + 1;
            int rightFractionStart = rightPoint == right.length() ? rightPoint : rightPoint + 1;
            int fractionLength = Math.max(
                    left.length() - leftFractionStart,
                    right.length() - rightFractionStart
            );
            for (int index = 0; index < fractionLength; index++) {
                char leftDigit = leftFractionStart + index < left.length()
                        ? left.charAt(leftFractionStart + index)
                        : '0';
                char rightDigit = rightFractionStart + index < right.length()
                        ? right.charAt(rightFractionStart + index)
                        : '0';
                int comparison = Character.compare(leftDigit, rightDigit);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        private static int decimalPoint(String value, int start) {
            int point = value.indexOf('.', start);
            return point < 0 ? value.length() : point;
        }
    }
}
