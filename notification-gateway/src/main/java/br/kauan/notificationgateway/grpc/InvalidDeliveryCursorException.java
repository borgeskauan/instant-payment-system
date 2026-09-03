package br.kauan.notificationgateway.grpc;

final class InvalidDeliveryCursorException extends RuntimeException {

    InvalidDeliveryCursorException() {
        super("invalid delivery cursor");
    }
}
