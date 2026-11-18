package jeju.datahub.batchops.service;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 실행별 텍스트 로그 조각 (TSK-4337 Step 7) — 배치 {@code text/plain} 본문 + 헤더 {@code X-Next-Byte}·{@code X-Job-Running}
 * 을 JSON 으로 감쌌다(프론트가 응답 헤더 노출 설정 없이 쓰게).
 *
 * @param content    로그 본문(배치가 마스킹한 것 · UTF-8 디코딩)
 * @param nextByte   다음 증분 조회의 {@code fromByte} — 🔴 <b>원본 파일 바이트</b> 기준이라 {@code content} 길이와 다르다
 * @param jobRunning 배치 프로세스에서 아직 돌고 있나 — {@code false} 면 폴링을 멈춘다(헤더가 없으면 null)
 */
@Schema(description = "실행별 텍스트 로그 조각")
public record BatchOpsLogVO(
        @Schema(description = "로그 본문 (마스킹 · UTF-8)") String content,
        @Schema(description = "다음 증분 조회 fromByte (원본 파일 바이트 기준)", example = "20480") Long nextByte,
        @Schema(description = "배치 프로세스에서 실행 중인가 (false 면 폴링 중단)") Boolean jobRunning) {
}
