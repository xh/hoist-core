package io.xh.hoist.websocket

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.IdentityService
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator

@Slf4j
@CompileStatic
class HoistWebSocketChannel implements LogSupport {

    static int SEND_TIME_LIMIT_MS = 1000
    static int BUFFER_SIZE_LIMIT_BYTES = 1000000

    final WebSocketSession session

    HoistWebSocketChannel(WebSocketSession session) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES)
    }

    String getKey() {
        return "${sessionUsername}@${session.id}"
    }

    void sendMessage(TextMessage message) {
        try {
            session.sendMessage(message)
        } catch (Exception e) {
            logErrorCompact("Failed to send message to $key", e)
        }
    }

    void close(CloseStatus status) {
        // TODO - should we pass in status here?
        session.close(status)
    }

    //------------------------
    // Implementation
    //------------------------
    private String getSessionUsername() {
        // TODO - should we be using apparent user?
        return (String) session.attributes[IdentityService.AUTH_USER_KEY] ?: 'anonymous'
    }
}
