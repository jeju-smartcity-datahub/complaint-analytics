package jeju.datahub.complaint.service;

/**
 * 민원 감정 분석 분포 응답 객체.
 *
 * * 가이드 §3 결정: 전체 단어 코드 {@code POSITIVE} / {@code NEUTRAL} / {@code NEGATIVE}
 *   (3글자 약어 {@code POS}/{NEG /NEU} 아님 — 가이드 §8 결정).
 * * 분모 = 감성 분석 완료된 전체(null 제외). §3 KPI + §8 카드 + §7 카드의 {@code topTypeRatio} 와 동일 단위 통일.
 *
 * * 가이드 §3 결정: 컬럼 적재 로직 미구현 상태에서는 각 필드 0 반환.
 */
public record SentimentDistVO(long positive, long neutral, long negative) {
}