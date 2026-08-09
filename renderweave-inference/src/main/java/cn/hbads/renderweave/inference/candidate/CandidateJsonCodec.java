package cn.hbads.renderweave.inference.candidate;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.exc.InvalidFormatException;
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
        }
        if (containsType(failure, "ValueInstantiationException") || invalidFormat != null) {
            return "CANDIDATE_DECODE_VALUE_INVALID";
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
