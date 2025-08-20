package com.clov.backend.domain.canvas.scheduler;

import com.clov.backend.common.redis.repository.RedisRepository;
import com.clov.backend.domain.canvas.dto.response.FullCanvasStateResponse;
import com.clov.backend.domain.room.websocket.WebSocketMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 일정 주기로 Canvas 전체 상태를 브로드캐스트하여 정합성을 유지하는 스케줄러입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasStateSyncScheduler {

    private final RedisRepository redisRepository;
    private final WebSocketMessageSender messageSender;

    /**
     * 5초마다 전체 상태를 브로드캐스트합니다.
     */
    @Scheduled(fixedRate = 5000)
    public void broadcastAllCanvasStates() {
        Set<String> activeRoomCodes = redisRepository.getAllRoomCodes(); // 예: canvas:* prefix 기반
        for (String roomCode : activeRoomCodes) {
            FullCanvasStateResponse response = redisRepository.getCanvasState(roomCode);

            var payload = Map.of(
                    "event", "canvas-sync",
                    "data", response
            );

            messageSender.broadcastToRoom(roomCode, payload);
            // //log.info("[CanvasStateSyncScheduler] position-sync → {}", roomCode);
        }
    }
}
