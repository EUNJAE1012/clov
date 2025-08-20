package com.clov.backend.domain.room.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebSocket을 통해 클라이언트에게 메시지를 전송하는 클래스입니다.
 * RedisSubscriber가 수신한 메시지를 해당 roomCode를 가진 클라이언트들에게 브로드캐스트합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketMessageSender {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    /**
     * 특정 roomCode에 해당하는 WebSocket 구독자들에게 메시지를 전송합니다.
     * @param roomCode 방 코드 (WebSocket 채널 구분용)
     * @param message 전달할 메시지 (직렬화된 JSON 문자열 혹은 DTO 객체)
     */
    public void broadcastToRoom(String roomCode, Object message) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            sessionManager.broadcast(roomCode, jsonMessage);
        } catch (Exception e) {
            //log.error("[WebSocketMessageSender] 메시지 직렬화 실패", e);
        }
    }
}