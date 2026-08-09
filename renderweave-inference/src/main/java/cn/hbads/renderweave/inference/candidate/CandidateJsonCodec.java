package cn.hbads.renderweave.inference.candidate;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;

public final class CandidateJsonCodec {
    public static final int MAX_CANDIDATE_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String write(CandidateBundle candidate) {
        try {
            var value = JSON.writeValueAsString(candidate);
            if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CANDIDATE_BYTES) {
                throw new InvalidCandidateContractException(
                        "CANDIDATE_TOO_LARGE", "Candidate exceeds 2 MiB", null
                );
            }
            return value;
        } catch (InvalidCandidateContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_WRITE_FAILED", "Candidate could not be serialized", exception
            );
        }
    }

    public CandidateBundle parse(String value) {
        if (value == null) {
            throw decodeFailure(
                    "CANDIDATE_DECODE_REQUIRED", "Candidate JSON is required", null
            );
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CANDIDATE_BYTES) {
            throw new InvalidCandidateContractException(
                    "CANDIDATE_TOO_LARGE", "CANDIDATE_DECODE_TOO_LARGE",
                    "Candidate exceeds 2 MiB", null
            );
        }
        if (value.isBlank()) {
            throw decodeFailure(
                    "CANDIDATE_DECODE_REQUIRED", "Candidate JSON is required", null
            );
        }
        try {
            return JSON.readValue(value, CandidateBundle.class);
        } catch (Exception exception) {
            throw decodeFailure(classifyDecodeFailure(exception),
                    "Candidate JSON does not match the strict contract", exception);
        }
    }

    private static InvalidCandidateContractException decodeFailure(
            String diagnosticCode,
            String message,
            Throwable cause
    ) {
        return new InvalidCandidateContractException(
                "CANDIDATE_JSON_INVALID", diagnosticCode, message, cause
        );
    }

    private static String classifyDecodeFailure(Throwable failure) {
        if (containsType(failure, "UnrecognizedPropertyException")) {
            return "CANDIDATE_DECODE_UNKNOWN_MEMBER";
        }
        var invalidFormat = findCause(failure, InvalidFormatException.class);
        if (invalidFormat != null) {
            var enumDiagnostic = enumInvalidDiagnostic(invalidFormat.getTargetType());
            if (enumDiagnostic != null) return enumDiagnostic;
            return formatInvalidDiagnostic(invalidFormat);
        }
        var valueInstantiation = findCause(failure, ValueInstantiationException.class);
        if (valueInstantiation != null) {
            return constructorInvalidDiagnostic(valueInstantiation);
        }
        if (containsType(failure, "MismatchedInputException")) {
            if (messageStartsWithForType(
                    failure, "MismatchedInputException", "trailing token (`jsontoken."
            )) {
                return "CANDIDATE_DECODE_TRAILING_CONTENT";
            }
            return "CANDIDATE_DECODE_SHAPE_INVALID";
        }
        if (containsType(failure, "StreamReadException")
                || containsType(failure, "UnexpectedEndOfInputException")) {
            if (messageStartsWithForType(failure, "StreamReadException", "duplicate field")
                    || messageStartsWithForType(
                            failure, "StreamReadException", "duplicate object property"
                    )) {
                return "CANDIDATE_DECODE_DUPLICATE_MEMBER";
            }
            return "CANDIDATE_DECODE_SYNTAX_INVALID";
        }
        return "CANDIDATE_DECODE_OTHER";
    }

    private static String enumInvalidDiagnostic(Class<?> targetType) {
        if (targetType == CandidateSource.class) {
            return "CANDIDATE_DECODE_ENUM_INVALID_SOURCE";
        }
        if (targetType == CandidateResolution.class) {
            return "CANDIDATE_DECODE_ENUM_INVALID_RESOLUTION";
        }
        if (targetType == CandidateEvidenceKind.class) {
            return "CANDIDATE_DECODE_ENUM_INVALID_EVIDENCE_KIND";
        }
        if (targetType == CandidateValueKind.class) {
            return "CANDIDATE_DECODE_ENUM_INVALID_VALUE_KIND";
        }
        if (targetType == CandidateReferenceKind.class) {
            return "CANDIDATE_DECODE_ENUM_INVALID_REFERENCE_KIND";
        }
        if (targetType != null && targetType.isEnum()) {
            return "CANDIDATE_DECODE_ENUM_INVALID_OTHER";
        }
        return null;
    }

    private static String formatInvalidDiagnostic(InvalidFormatException failure) {
        for (var index = failure.getPath().size() - 1; index >= 0; index--) {
            var slot = formatSlot(failure.getPath().get(index).getPropertyName());
            if (slot != null) return "CANDIDATE_DECODE_FORMAT_INVALID_" + slot;
        }
        return "CANDIDATE_DECODE_FORMAT_INVALID_OTHER";
    }

    private static String formatSlot(String propertyName) {
        if ("contractVersion".equals(propertyName)) return "CONTRACT_VERSION";
        if ("rootCandidateSchemaId".equals(propertyName)) return "ROOT_SCHEMA_ID";
        if ("candidateSchemaId".equals(propertyName)) return "SCHEMA_ID";
        if ("candidateFieldId".equals(propertyName)) return "FIELD_ID";
        if ("proposedSchemaKey".equals(propertyName)) return "SCHEMA_KEY";
        if ("proposedFieldKey".equals(propertyName)) return "FIELD_KEY";
        if ("displayName".equals(propertyName)) return "DISPLAY_NAME";
        if ("required".equals(propertyName)) return "FIELD_REQUIRED";
        if ("confidenceBps".equals(propertyName)) return "ASSESSMENT_CONFIDENCE";
        if ("inferred".equals(propertyName)) return "ASSESSMENT_INFERRED";
        if ("sampleIndex".equals(propertyName)) return "EVIDENCE_SAMPLE_INDEX";
        if ("artifactId".equals(propertyName)) return "EVIDENCE_ARTIFACT_ID";
        if ("jsonPointer".equals(propertyName)) return "EVIDENCE_JSON_POINTER";
        if ("left".equals(propertyName) || "top".equals(propertyName)
                || "right".equals(propertyName) || "bottom".equals(propertyName)) {
            return "BOUNDING_BOX_COORDINATE";
        }
        if ("schemaKey".equals(propertyName)) return "REFERENCE_SCHEMA_KEY";
        if ("versionTag".equals(propertyName)) return "REFERENCE_VERSION_TAG";
        return null;
    }

    private static String constructorInvalidDiagnostic(ValueInstantiationException failure) {
        var rawType = failure.getType() == null ? null : failure.getType().getRawClass();
        var cause = failure.getCause();
        var member = cause instanceof NullPointerException ? cause.getMessage() : null;
        return "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_" + constructorSlot(rawType, member);
    }

    private static String constructorSlot(Class<?> rawType, String member) {
        if (rawType == CandidateBundle.class) {
            if ("contractVersion".equals(member)) return "BUNDLE_CONTRACT_VERSION";
            if ("rootCandidateSchemaId".equals(member)) return "BUNDLE_ROOT_SCHEMA_ID";
            if ("schemas".equals(member)) return "BUNDLE_SCHEMAS";
            return "BUNDLE";
        }
        if (rawType == CandidateSchema.class) {
            if ("candidateSchemaId".equals(member)) return "SCHEMA_ID";
            if ("source".equals(member)) return "SCHEMA_SOURCE";
            if ("assessment".equals(member)) return "SCHEMA_ASSESSMENT";
            if ("fields".equals(member)) return "SCHEMA_FIELDS";
            return "SCHEMA";
        }
        if (rawType == CandidateField.class) {
            if ("candidateFieldId".equals(member)) return "FIELD_ID";
            if ("value".equals(member)) return "FIELD_VALUE";
            if ("source".equals(member)) return "FIELD_SOURCE";
            if ("assessment".equals(member)) return "FIELD_ASSESSMENT";
            return "FIELD";
        }
        if (rawType == CandidateValue.class) {
            if ("kind".equals(member)) return "VALUE_KIND";
            if ("observedKinds".equals(member)) return "VALUE_OBSERVED_KINDS";
            if ("constraints".equals(member)) return "VALUE_CONSTRAINTS";
            return "VALUE";
        }
        if (rawType == CandidateAssessment.class) {
            if ("resolution".equals(member)) return "ASSESSMENT_RESOLUTION";
            if ("evidence".equals(member)) return "ASSESSMENT_EVIDENCE";
            return "ASSESSMENT";
        }
        if (rawType == CandidateReference.class) return "REFERENCE";
        if (rawType == CandidateEvidence.class) return "EVIDENCE";
        if (rawType == CandidateBoundingBox.class) return "BOUNDING_BOX";
        return "OTHER";
    }

    private static boolean containsType(Throwable failure, String simpleName) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (isType(cause, simpleName)) return true;
        }
        return false;
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> expectedType) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (expectedType.isInstance(cause)) return expectedType.cast(cause);
        }
        return null;
    }

    private static boolean messageStartsWithForType(
            Throwable failure,
            String simpleName,
            String prefix
    ) {
        var expected = prefix.toLowerCase(java.util.Locale.ROOT);
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (!isType(cause, simpleName)) continue;
            var message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).startsWith(expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isType(Throwable failure, String simpleName) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals(simpleName)) return true;
        }
        return false;
    }
}
