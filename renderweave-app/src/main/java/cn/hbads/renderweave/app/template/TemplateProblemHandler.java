package cn.hbads.renderweave.app.template;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestControllerAdvice(assignableTypes = TemplateController.class)
final class TemplateProblemHandler {
    @ExceptionHandler(InvalidTemplateApiRequestException.class)
    ResponseEntity<TemplateController.TemplateProblemResponse> invalidRequest(
            InvalidTemplateApiRequestException invalid
    ) {
        return invalidRequestProblem(invalid.getMessage());
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<TemplateController.TemplateProblemResponse> invalidTransportRequest(
            Exception ignored
    ) {
        return invalidRequestProblem("Template request parameters or body are invalid");
    }

    private ResponseEntity<TemplateController.TemplateProblemResponse> invalidRequestProblem(
            String detail
    ) {
        var status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new TemplateController.TemplateProblemResponse(
                "urn:renderweave:problem:template-request-invalid",
                "Template request invalid",
                status.value(),
                detail,
                null,
                "TEMPLATE_REQUEST_INVALID",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null
        ));
    }
}
