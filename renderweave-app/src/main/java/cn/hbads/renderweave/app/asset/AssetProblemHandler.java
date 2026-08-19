package cn.hbads.renderweave.app.asset;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.UUID;

@RestControllerAdvice(assignableTypes = AssetController.class)
final class AssetProblemHandler {
    @ExceptionHandler(InvalidAssetApiRequestException.class)
    ResponseEntity<AssetController.AssetProblemResponse> invalidRequest(
            InvalidAssetApiRequestException invalid
    ) {
        return invalidRequestProblem(invalid.getMessage());
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<AssetController.AssetProblemResponse> invalidTransportRequest(
            Exception ignored
    ) {
        return invalidRequestProblem("Asset request parameters, parts or body are invalid");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<AssetController.AssetProblemResponse> payloadTooLarge(
            MaxUploadSizeExceededException ignored
    ) {
        var status = HttpStatus.PAYLOAD_TOO_LARGE;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new AssetController.AssetProblemResponse(
                "urn:renderweave:problem:asset-payload-too-large",
                "Asset payload too large",
                status.value(),
                "The multipart payload exceeds the bounded Asset transport limit.",
                null,
                "ASSET_PAYLOAD_TOO_LARGE",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null
        ));
    }

    private ResponseEntity<AssetController.AssetProblemResponse> invalidRequestProblem(
            String detail
    ) {
        var status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new AssetController.AssetProblemResponse(
                "urn:renderweave:problem:asset-request-invalid",
                "Asset request invalid",
                status.value(),
                detail,
                null,
                "ASSET_REQUEST_INVALID",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null
        ));
    }
}
