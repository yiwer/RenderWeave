package cn.hbads.renderweave.inference;

final class InvalidInferenceApiRequestException extends IllegalArgumentException {
    InvalidInferenceApiRequestException(String message) {
        super(message);
    }

    InvalidInferenceApiRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
