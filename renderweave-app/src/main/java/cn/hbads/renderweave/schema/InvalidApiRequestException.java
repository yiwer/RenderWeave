package cn.hbads.renderweave.schema;

final class InvalidApiRequestException extends IllegalArgumentException {

    InvalidApiRequestException(String message) {
        super(message);
    }

    InvalidApiRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
