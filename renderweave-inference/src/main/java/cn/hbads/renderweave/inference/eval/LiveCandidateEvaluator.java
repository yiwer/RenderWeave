package cn.hbads.renderweave.inference.eval;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Version-independent structural scorer; UUIDs and display wording deliberately do not affect quality. */
public final class LiveCandidateEvaluator {
    public LiveEvaluationResult evaluate(
            LiveEvaluationCase gold,
            CandidateBundle candidate,
            List<CandidateProblem> problems
    ) {
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(candidate, "candidate");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        var root = candidate.schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(candidate.rootCandidateSchemaId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Candidate root schema is missing"));
        var activeSchemas = candidate.schemas().stream()
                .filter(schema -> schema.assessment().resolution() != CandidateResolution.REMOVED)
                .toList();
        var fields = new LinkedHashMap<String, CandidateField>();
        root.fields().stream()
                .filter(field -> field.assessment().resolution() != CandidateResolution.REMOVED)
                .forEach(field -> fields.putIfAbsent(field.proposedFieldKey(), field));

        var expected = new TreeSet<>(gold.expectedRootShapes().keySet());
        var actual = new TreeSet<>(fields.keySet());
        var missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        var unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        var matches = new TreeSet<>(expected);
        matches.retainAll(actual);
        var mismatches = new ArrayList<String>();
        for (var fieldKey : matches) {
            var actualShape = shape(fields.get(fieldKey).value());
            var expectedShape = gold.expectedRootShapes().get(fieldKey);
            if (!expectedShape.equals(actualShape)) {
                mismatches.add(fieldKey + ":" + expectedShape + "!=" + actualShape);
            }
        }

        var allItems = new ArrayList<cn.hbads.renderweave.inference.candidate.CandidateAssessment>();
        for (var schema : activeSchemas) {
            if (schema.source() == CandidateSource.AI) allItems.add(schema.assessment());
            schema.fields().stream()
                    .filter(field -> field.source() == CandidateSource.AI)
                    .filter(field -> field.assessment().resolution() != CandidateResolution.REMOVED)
                    .map(CandidateField::assessment)
                    .forEach(allItems::add);
        }
        var evidenceCount = allItems.stream().filter(item -> !item.evidence().isEmpty()).count();
        var optionalCount = fields.values().stream().filter(field -> !field.required()).count();
        var blockerCount = (int) problems.stream()
                .filter(problem -> problem.severity() == CandidateProblemSeverity.BLOCKER)
                .count();
        var precision = ratio(matches.size(), actual.size());
        var recall = ratio(matches.size(), expected.size());
        var shapeAccuracy = ratio(matches.size() - mismatches.size(), expected.size());
        var evidenceCoverage = ratio(evidenceCount, allItems.size());
        var optionalitySafety = ratio(optionalCount, fields.size());
        var passed = activeSchemas.size() == gold.expectedSchemaCount()
                && precision == 10_000 && recall == 10_000 && shapeAccuracy == 10_000
                && evidenceCoverage == 10_000 && optionalitySafety == 10_000;
        return new LiveEvaluationResult(
                gold.caseId(), passed, activeSchemas.size(), gold.expectedSchemaCount(),
                precision, recall, shapeAccuracy, evidenceCoverage, optionalitySafety, blockerCount,
                List.copyOf(missing), List.copyOf(unexpected), List.copyOf(mismatches)
        );
    }

    private static String shape(CandidateValue value) {
        if (value.kind() == CandidateValueKind.ARRAY) {
            return "ARRAY:" + shape(Objects.requireNonNull(value.items(), "array items"));
        }
        return value.kind().name();
    }

    private static int ratio(long numerator, long denominator) {
        if (denominator == 0) return 10_000;
        return (int) Math.floorDiv(numerator * 10_000L, denominator);
    }
}
