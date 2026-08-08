package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.InferenceCandidateNotFoundException;
import cn.hbads.renderweave.inference.candidate.CandidateApplyBlockedException;
import cn.hbads.renderweave.inference.candidate.CandidateApplyConflictException;
import cn.hbads.renderweave.inference.candidate.CandidateMaterializationException;
import cn.hbads.renderweave.inference.candidate.InferenceCandidateRevisionConflictException;
import cn.hbads.renderweave.inference.candidate.InvalidCandidateContractException;
import cn.hbads.renderweave.inference.candidate.InvalidCandidateEditException;
import cn.hbads.renderweave.inference.input.InvalidInferenceInputException;
import cn.hbads.renderweave.inference.run.InferenceIdempotencyConflictException;
import cn.hbads.renderweave.inference.run.InferenceRunNotFoundException;
import cn.hbads.renderweave.inference.run.InvalidInferenceRunTransitionException;
import cn.hbads.renderweave.schema.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
final class InferenceProblemHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiProblem> payloadTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Inference payload too large",
                "INFERENCE_PAYLOAD_TOO_LARGE",
                "The multipart payload exceeds the bounded inference transport limit.",
                request, null, null);
    }

    @ExceptionHandler(LiveInferenceUnavailableException.class)
    ResponseEntity<ApiProblem> liveUnavailable(
            LiveInferenceUnavailableException exception,
            HttpServletRequest request
    ) {
        var status = "LIVE_UPLOAD_NOT_AUTHORIZED".equals(exception.code())
                ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
        return problem(status, "Live inference unavailable", exception.code(),
                exception.getMessage(), request, null, null);
    }

    @ExceptionHandler(InvalidInferenceApiRequestException.class)
    ResponseEntity<ApiProblem> invalidRequest(
            InvalidInferenceApiRequestException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.BAD_REQUEST, "Inference request invalid", "INVALID_REQUEST",
                exception.getMessage(), request, null, null);
    }

    @ExceptionHandler(InvalidInferenceInputException.class)
    ResponseEntity<ApiProblem> invalidInput(
            InvalidInferenceInputException exception,
            HttpServletRequest request
    ) {
        var violation = new ApiProblem.ApiViolation(
                exception.code(), exception.pointer(), exception.args(), exception.getMessage()
        );
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Inference input invalid", exception.code(),
                exception.getMessage(), request, List.of(violation), null);
    }

    @ExceptionHandler({InvalidCandidateContractException.class, InvalidCandidateEditException.class})
    ResponseEntity<ApiProblem> invalidCandidate(RuntimeException exception, HttpServletRequest request) {
        var code = exception instanceof InvalidCandidateContractException contract
                ? contract.code() : ((InvalidCandidateEditException) exception).code();
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Candidate edit invalid", code,
                exception.getMessage(), request, null, null);
    }

    @ExceptionHandler(CandidateMaterializationException.class)
    ResponseEntity<ApiProblem> materialization(
            CandidateMaterializationException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Candidate materialization invalid",
                exception.code(), exception.getMessage(), request, null, null);
    }

    @ExceptionHandler(CandidateApplyBlockedException.class)
    ResponseEntity<ApiProblem> applyBlocked(
            CandidateApplyBlockedException exception,
            HttpServletRequest request
    ) {
        var violations = exception.problems().stream()
                .map(item -> new ApiProblem.ApiViolation(
                        item.code(), item.pointer(),
                        new java.util.LinkedHashMap<String, Object>(item.args()),
                        "Candidate review blocker"
                ))
                .toList();
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Candidate apply blocked",
                "CANDIDATE_APPLY_BLOCKED", exception.getMessage(), request, violations, null);
    }

    @ExceptionHandler(CandidateApplyConflictException.class)
    ResponseEntity<ApiProblem> applyConflict(
            CandidateApplyConflictException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "Candidate apply conflict", exception.code(),
                exception.getMessage(), request, null, null);
    }

    @ExceptionHandler(InferenceCandidateRevisionConflictException.class)
    ResponseEntity<ApiProblem> candidateConflict(
            InferenceCandidateRevisionConflictException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "Candidate revision conflict", "CANDIDATE_REVISION_CONFLICT",
                "The Candidate changed after this review loaded. Local changes were not written.",
                request, null, exception.currentRevision());
    }

    @ExceptionHandler(InferenceIdempotencyConflictException.class)
    ResponseEntity<ApiProblem> idempotencyConflict(
            InferenceIdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "Idempotency conflict", "INFERENCE_IDEMPOTENCY_CONFLICT",
                exception.getMessage(), request, null, null);
    }

    @ExceptionHandler({InferenceRunNotFoundException.class, InferenceCandidateNotFoundException.class})
    ResponseEntity<ApiProblem> notFound(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Inference run not found", "INFERENCE_RUN_NOT_FOUND",
                "No inference run or review candidate exists for this identifier.", request, null, null);
    }

    @ExceptionHandler(InvalidInferenceRunTransitionException.class)
    ResponseEntity<ApiProblem> transition(
            InvalidInferenceRunTransitionException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "Inference state conflict", "INFERENCE_STATE_CONFLICT",
                exception.getMessage(), request, null, null);
    }

    private static ResponseEntity<ApiProblem> problem(
            HttpStatus status,
            String title,
            String code,
            String detail,
            HttpServletRequest request,
            List<ApiProblem.ApiViolation> violations,
            Long revision
    ) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ApiProblem(
                        "https://renderweave.local/problems/" + code.toLowerCase().replace('_', '-'),
                        title, status.value(), detail, request.getRequestURI(), code,
                        UUID.randomUUID().toString(), violations, revision
                ));
    }
}
