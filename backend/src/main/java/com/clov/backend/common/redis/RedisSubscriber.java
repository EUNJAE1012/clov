package com.clov.backend.common.redis;

import com.clov.backend.common.redis.repository.RedisRepository;
import com.clov.backend.domain.background.dto.response.BackgroundResponse;
import com.clov.backend.domain.background.entity.Background;
import com.clov.backend.domain.background.repository.BackgroundRepository;
import com.clov.backend.domain.canvas.dto.CanvasStateDto;
import com.clov.backend.domain.canvas.dto.response.FullCanvasStateResponse;
import com.clov.backend.domain.room.websocket.WebSocketMessageSender;
import com.clov.backend.domain.roomstate.dto.RoomMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redis에서 발행된 메시지를 수신하여 WebSocket을 통해 브로드캐스트하는 역할을 담당합니다.
 * 클라이언트가 보낸 "join-room", "update-position" 등의 이벤트를 구독하여,
 * 다른 사용자에게 "user-joined", "position-updated" 등의 형태로 전달합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final WebSocketMessageSender messageSender;
    private final RedisRepository redisRepository;
    private final BackgroundRepository backgroundRepository;

    @Value("${cloud.aws.s3.background-base-url}")   // 예) https://my-bucket.s3.ap-northeast-2.amazonaws.com/
    private String baseUrl;  //s3서버 url
    @Value("${cloud.aws.s3.bucket}")
    private String bucket; //버킷이름

    /**
     * Redis 채널로부터 수신된 메시지를 처리합니다.
     * @param message Redis에서 발행된 메시지
     * @param pattern 구독한 채널 패턴 (사용하지 않음)
     */

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String raw = new String(message.getBody());
            //log.info("[RedisSubscriber] 수신된 원본 메시지: {}", raw);

            // JSON → Map
            String json = objectMapper.readValue(raw, String.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonMap = objectMapper.readValue(json, Map.class);

            String event = (String) jsonMap.get("event");

            // data 필드 내부 파싱
            @SuppressWarnings("unchecked")
            Map<String, Object> dataMap = (Map<String, Object>) jsonMap.get("data");

            String roomCode = (String) dataMap.get("roomCode");
            String clientIdStr = (String) dataMap.get("clientId");
            UUID clientId = clientIdStr != null ? UUID.fromString(clientIdStr) : null;

            CanvasStateDto state = null;
            if (dataMap.get("state") != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stateMap = (Map<String, Object>) dataMap.get("state");

                // rotation 필드가 존재하면 0~359 범위로 정규화하고, 없으면 기본값 0을 설정
                Integer rotationRaw = stateMap.get("rotation") != null
                        ? ((Number) stateMap.get("rotation")).intValue() % 360
                        : 0;

                Boolean isMicOn = stateMap.get("isMicOn") != null ? (Boolean) stateMap.get("isMicOn") : false;
                String overlay = stateMap.get("overlay") != null ? (String) stateMap.get("overlay") : null;
                state = CanvasStateDto.builder()
                        .x(((Number) stateMap.get("x")).intValue())
                        .y(((Number) stateMap.get("y")).intValue())
                        .scale(((Number) stateMap.get("scale")).doubleValue())
                        .opacity(((Number) stateMap.get("opacity")).doubleValue())
                        .mode(stateMap.get("mode") != null ? ((Number) stateMap.get("mode")).intValue() : null)
                        .filter((String) stateMap.get("filter"))
                        .rotation(rotationRaw)
                        .isMicOn(isMicOn)
                        .overlay(overlay)
                        .build();
            }

            switch (event) {
                case "join-room" -> {
                    // nickname은 redis에서 조회 (API에서 저장했기 때문)
                    String nicknameFromRedis = redisRepository.getNickname(roomCode, clientId);

                    // 전체 참가자 정보 Map<String, String>: clientId → nickname
                    Map<String, String> participants = redisRepository.getParticipantNicknames(roomCode);

                    // Map 구조로 payload 구성
                    Map<String, Object> payload = Map.of(
                            "event", "user-joined",
                            "data", Map.of(
                                    "roomCode", roomCode,
                                    "newComer", Map.of(
                                            "clientId", clientId.toString(),
                                            "nickname", nicknameFromRedis
                                    ),
                                    "participants", participants
                            )
                    );

                    // 브로드캐스트
                    messageSender.broadcastToRoom(roomCode, payload);
                    //log.info("[RedisSubscriber] join-room 처리 완료");
                }
                case "update-state" -> {
                    //log.info("[RedisSubscriber] update-state 이벤트 처리 시작");

                    // 상태 저장
                    redisRepository.saveCanvasState(roomCode, clientId, state);

                    // 단일 참가자 정보 가져오기
                    String nickname = redisRepository.getNickname(roomCode, clientId);
                    boolean isHost = redisRepository.isHost(roomCode, clientId);

                    // 단일 참가자 응답 전송
                    Map<String, Object> stateMap = new HashMap<>();
                    stateMap.put("x", state.getX());
                    stateMap.put("y", state.getY());
                    stateMap.put("scale", state.getScale());
                    stateMap.put("opacity", state.getOpacity());
                    if (state.getMode() != null) stateMap.put("mode", state.getMode());
                    if (state.getFilter() != null) stateMap.put("filter", state.getFilter());
                    if (state.getRotation() != null) stateMap.put("rotation", state.getRotation());
                    if (state.getIsMicOn() != null) stateMap.put("isMicOn", state.getIsMicOn());
                    if (state.getOverlay() != null) stateMap.put("overlay", state.getOverlay());
                    Map<String, Object> data = new HashMap<>();
                    data.put("clientId", clientId.toString());
                    data.put("nickname", nickname != null ? nickname : ""); // or fallback
                    data.put("isHost", isHost);
                    data.put("state", stateMap);

                    Map<String, Object> singlePayload = new HashMap<>();
                    singlePayload.put("event", "state-updated");
                    singlePayload.put("data", data);

                    messageSender.broadcastToRoom(roomCode, singlePayload);
                    //log.info("[RedisSubscriber] update-state 처리 완료");
                }
                /*요 배경 변경 이벤트는 안 쓰는데 혹여나 후에 필요 있을까봐 아직 안지웠습니다.*/
                case "change-background" -> {
                    log.info("[RedisSubscriber] change-background 이벤트 처리 시작");

                    // 방장 확인
                    if (!redisRepository.isHost(roomCode, clientId)) {
                        //log.warn("[RedisSubscriber] 비방장이 배경 변경 시도: {}", clientId);
                        return;
                    }
                    
                    // background 맵 꺼내기 요청에서 background url을 파싱하는건 보안상 좋지 않아보입니다. 클라이언트쪽에 DB에 있는 정보가 하드 코딩되어 있어야 함 
                    // @SuppressWarnings("unchecked")
                    // Map<String, Object> backgroundMap = (Map<String, Object>) dataMap.get("background");
                    // if (backgroundMap == null) {
                    //     //log.warn("[RedisSubscriber] background 필드가 누락됨");
                    //     return;
                    // }
                    // String backgroundUrl = (String) backgroundMap.get("backgroundUrl"); 
                    // String backgroundTitle = (String) backgroundMap.get("backgroundTitle");
                    
                    //요청에서는 backgroundId만 파싱
                    Object backgroundIdObj = dataMap.get("backgroundId");
                    if (backgroundIdObj == null) {
                        log.warn("[RedisSubscriber] backgroundId 필드가 누락됨");
                        return;
                    }
                    Long backgroundId = ((Number) backgroundIdObj).longValue();
                    
                    String backgroundUrl;
                    String backgroundTitle;
                    //DB에서 꺼내오기
                    if (backgroundId == -1) {
                        // ✅ 커스텀 배경 처리 (서비스와 동일한 로직)
                        backgroundUrl = String.format("%sbackgrounds/%s.png", ensureSlash(baseUrl), roomCode);
                        backgroundTitle = roomCode + "Custom";
                    } else {
                        // ✅ 기본 배경 처리
                        Background bg = backgroundRepository.findById(backgroundId)
                            .orElseThrow(() -> new IllegalArgumentException("배경이 존재하지 않습니다."));
                        backgroundUrl = String.format("%sbackgrounds/%d.png", ensureSlash(baseUrl), bg.getBackgroundId());
                        backgroundTitle = bg.getBackgroundTitle();
                    }

                    // time stamp 추가  
                    String cacheBustedUrl = backgroundUrl + "?v=" + System.currentTimeMillis();
                    // Redis에 배경 저장
                    redisRepository.saveBackground(roomCode, new CanvasStateDto.BackgroundDto(backgroundUrl, backgroundTitle));

                    // 클라이언트에게 브로드캐스트
                    var broadcast = Map.of(
                            "event", "background-changed",
                            "data", Map.of(
                                    "roomCode", roomCode,
                                    "background", Map.of(
                                            "backgroundUrl", cacheBustedUrl,
                                            "backgroundTitle", backgroundTitle
                                    )
                            )
                    );
                    messageSender.broadcastToRoom(roomCode, broadcast);
                    //log.info("[RedisSubscriber] background-changed 브로드캐스트 완료");
                }

                case "start-recording" -> {
                    //log.info("[RedisSubscriber] start-recording 이벤트 처리 시작");

                    // 방장 권한 확인
                    if (!redisRepository.isHost(roomCode, clientId)) {
                        //log.warn("[RedisSubscriber] 비방장이 녹화 시작 시도: {}", clientId);
                        return;
                    }

                    // duration 가져오기
                    Integer duration = (Integer) dataMap.getOrDefault("duration", 30); // 기본 30초

                    // countdown-start 이벤트 생성 (type: video)
                    var payload = Map.of(
                            "event", "countdown-start",
                            "data", Map.of(
                                    "roomCode", roomCode,
                                    "startedAt", OffsetDateTime.now().toString(),
                                    "duration", duration+3,
                                    "type", "video"
                            )
                    );

                    messageSender.broadcastToRoom(roomCode, payload);
                    //log.info("[RedisSubscriber] start-recording 처리 완료");
                }


                case "start-photo" -> {
                    //log.info("[RedisSubscriber] start-photo 이벤트 처리 시작");

                    // 방장 권한 확인
                    if (!redisRepository.isHost(roomCode, clientId)) {
                        //log.warn("[RedisSubscriber] 비방장이 사진 촬영 시도: {}", clientId);
                        return;
                    }

                    // countdown-start 이벤트 생성 (type: photo, duration 고정 3초)
                    var payload = Map.of(
                            "event", "countdown-start",
                            "data", Map.of(
                                    "roomCode", roomCode,
                                    "startedAt", OffsetDateTime.now().toString(),
                                    "duration", 3,
                                    "type", "photo"
                            )
                    );

                    messageSender.broadcastToRoom(roomCode, payload);
                    //log.info("[RedisSubscriber] start-photo 처리 완료");
                }

                case "assign-host" -> {
                    //log.info("[RedisSubscriber] assign-host 이벤트 처리 시작");

                    String fromClientIdStr = (String) dataMap.get("from");
                    String toClientIdStr = (String) dataMap.get("to");

                    if (fromClientIdStr == null || toClientIdStr == null) {
                        //log.warn("[RedisSubscriber] 필수 필드 누락 - from: {}, to: {}", fromClientIdStr, toClientIdStr);
                        return;
                    }

                    // 닉네임 조회
                    Map<String, String> participants = redisRepository.getParticipantNicknames(roomCode);
                    String previousNickname = participants.getOrDefault(fromClientIdStr, "");
                    String newNickname = participants.getOrDefault(toClientIdStr, "");

                    Map<String, Object> payload = Map.of(
                            "event", "host-changed",
                            "data", Map.of(
                                    "roomCode", roomCode,
                                    "newHost", Map.of(
                                            "clientId", toClientIdStr,
                                            "nickname", newNickname
                                    ),
                                    "previousHost", Map.of(
                                            "clientId", fromClientIdStr,
                                            "nickname", previousNickname
                                    )
                            )
                    );

                    messageSender.broadcastToRoom(roomCode, payload);
                    //log.info("[RedisSubscriber] assign-host 브로드캐스트 완료: {} → {}", fromClientIdStr, toClientIdStr);
                }
                case "leave-room" -> {
                    //log.info("[RedisSubscriber] leave-room 이벤트 처리 시작");
                    boolean isHost = redisRepository.isHost(roomCode, clientId);

                    //닉네임도 출력하기 위해..
                    String nicknameFromRedis = redisRepository.getNickname(roomCode, clientId);
                    // 나가고 참여중인 사람들 다시 불러오기
                    Map<String, String> participants = redisRepository.getParticipantNicknames(roomCode);

                    // event, data 구조로 응답 구성
                    Map<String, Object> payload = Map.of(
                            "event", "user-left",
                            "data", Map.of(
                                    "roomCode", roomCode,
                                    "lastLeaver", Map.of(
                                            "clientId", clientId.toString(),
                                            "nickname", nicknameFromRedis,
                                            "isHost", isHost
                                    ),
                                    "participants", participants
                            )
                    );

                    // WebSocket으로 브로드캐스트
                    messageSender.broadcastToRoom(roomCode, payload);

                    //log.info("[RedisSubscriber] leave-room 처리 완료");
                }
                default -> {
                    //log.warn("[RedisSubscriber] 정의되지 않은 이벤트 수신됨: {}", event);
                }
            }
        } catch (Exception e) {
            //log.error("[RedisSubscriber] 메시지 처리 실패 - 오류: {}", e.getMessage(), e);
        }
    }

    
    /** baseUrl에 끝 슬래시가 없을 때 하나 붙여줌 */
    private String ensureSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }
}
