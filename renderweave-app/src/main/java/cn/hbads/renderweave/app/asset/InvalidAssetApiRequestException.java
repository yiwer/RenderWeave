package cn.hbads.renderweave.app.asset;

final class InvalidAssetApiRequestException extends RuntimeException {
    InvalidAssetApiRequestException(String message) {
        super(message);
    }

    InvalidAssetApiRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
