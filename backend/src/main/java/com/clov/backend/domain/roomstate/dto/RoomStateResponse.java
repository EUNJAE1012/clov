package com.clov.backend.domain.roomstate.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Redis에 저장된 방의 전체 상태 정보를 클라이언트에게 전달하기 위한 DTO입니다.
 * - 참가자 상태 목록 (clientId, nickname, 위치 등)
 * - 배경 정보 (이미지 URL, 제목)
 * - 방 코드 포함
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomStateResponse {

    /** 방 코드 */
    private String roomCode;

    /** 배경 정보 */
    private BackgroundInfoDTO background;

    /** 참가자 목록 */
    private List<ParticipantCanvasDTO> participants;

    /**
     * 참가자 개별 상태를 담는 DTO
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParticipantCanvasDTO {
        private UUID clientId;
        private String nickname;
        private Integer x;
        private Integer y;
        private Double scale;
        private Double opacity;
        private Boolean isHost;
    }

    /**
     * 방의 배경 정보를 담는 DTO
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BackgroundInfoDTO {
        private String backgroundUrl;
        private String backgroundTitle;
    }
}
