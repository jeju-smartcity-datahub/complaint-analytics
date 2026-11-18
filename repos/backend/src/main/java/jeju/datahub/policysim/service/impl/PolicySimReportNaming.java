package jeju.datahub.policysim.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 정책 시뮬레이션 시나리오 비교 보고서의 이름·기간 설명 — 이 보고서에서 <b>두 문자열의 유일한 출처</b>.
 *
 * <p>보고서명 = {@code {YYYYMMDD}({n})_정책 시뮬레이션 시나리오 비교} (사용자 확정 2026-10-14).
 * <ul>
 *   <li>{@code YYYYMMDD} = 보고서 행의 {@code crt_ymd} 와 <b>같은 {@code now}</b> 의 날짜 — 따로 시간대를 계산하지 않는다</li>
 *   <li>{@code n} = 같은 생성자 · 같은 날 이름의 최대 순번 + 1 ({@code PolicySimMapper.selectMaxRptpSeqOfDay}) — 1 부터</li>
 * </ul>
 * 🔴 형식을 바꾸면 {@code PolicySimMapper.xml} 의 {@code selectMaxRptpSeqOfDay} 정규식도 함께 고친다 —
 * 갈리면 순번이 매번 {@code (1)} 로 돌아간다. 내려받는 파일 이름도 이 값이다(프론트가 응답 {@code rptpNm} 을 그대로 쓴다).
 */
final class PolicySimReportNaming {

    /** 보고서명의 이름 부분 — 날짜·순번 뒤에 {@code _} 로 이어진다 */
    static final String RPTP_NM_TITLE = "정책 시뮬레이션 시나리오 비교";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private PolicySimReportNaming() {
    }

    /** {@code now} 의 날짜 {@code YYYYMMDD} — 이름과 순번 조회가 같은 값을 쓴다 */
    static String ymd(LocalDateTime now) {
        return now.format(YMD);
    }

    /**
     * 보고서명 {@code {ymd}({seq})_정책 시뮬레이션 시나리오 비교}.
     *
     * @param ymd {@link #ymd(LocalDateTime)} 의 결과
     * @param seq 그날의 순번 (1-base)
     */
    static String rptpNm(String ymd, int seq) {
        return ymd + "(" + seq + ")_" + RPTP_NM_TITLE;
    }

    /**
     * 분석기간설명 {@code 예측 기간 {predcMm}개월} — 대표 시나리오의 예측 기간(사용자 확정 2026-10-14).
     * 기간이 없으면 {@code null}(지어내지 않는다).
     */
    static String anlsPrdExpln(Integer predcMm) {
        return predcMm == null ? null : "예측 기간 " + predcMm + "개월";
    }
}
