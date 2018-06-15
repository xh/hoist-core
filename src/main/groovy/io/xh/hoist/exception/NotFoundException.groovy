package io.xh.hoist.exception

class NotFoundException extends RuntimeException {
    NotFoundException(String message = "Not Found") {
        super(message)
    }
}
