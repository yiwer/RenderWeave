package cn.hbads.renderweave.app.validation;

import cn.hbads.renderweave.schema.ApiProblem;
import cn.hbads.renderweave.validation.InvalidValidationRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = RootDocumentValidationController.class)
final class ValidationProblemHandler {

    @ExceptionHandler(InvalidValidationRequestException.class)
    ResponseEntity<ApiProblem> invalidRequest(
            InvalidValidationRequestException exception,
            HttpServletRequest request
    ) {
        var status = exception.kind() == InvalidValidationRequestException.Kind.LIMIT_EXCEEDED
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;
        var body = new ApiProblem(
                "about:blank",
                exception.kind() == InvalidValidationRequestException.Kind.LIMIT_EXCEEDED
                        ? "Validation payload limit exceeded"
                        : "Validation request invalid",
                status.value(),
                exception.getMessage(),
                request.getRequestURI(),
                exception.code(),
                UUID.randomUUID().toString(),
                List.of(new ApiProblem.ApiViolation(
                        exception.code(),
                        exception.pointer(),
                        exception.messageArgs(),
                        exception.getMessage()
                )),
                null
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
