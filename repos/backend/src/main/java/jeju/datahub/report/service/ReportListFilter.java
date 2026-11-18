package jeju.datahub.report.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.ToString;

/**
 * 보고서 목록 필터 — 정규화된 값 묶음.
 *
 * <p>🔴 <b>정규화는 여기 한 곳이다</b> — 목록({@code selectReportList})과 총건수({@code selectReportCount})가
 * 같은 인스턴스를 받는다. 두 쿼리가 각자 값을 다듬으면 조건이 갈려 마지막 페이지가 빈다.
 *
 * <ul>
 *   <li>{@code keyword} — 앞뒤 공백 제거, 빈 문자열이면 {@code null}(미적용)</li>
 *   <li>{@code anlsTypeCd} — 빈 문자열이면 {@code null}</li>
 *   <li>{@code fromDt} = {@code fromYmd} 00:00 · {@code toDtExclusive} = {@code toYmd + 1일} 00:00 —
 *       🔴 {@code crt_ymd} 가 {@code TIMESTAMP} 라 {@code <= toYmd} 로 쓰면 종료일 당일분이 빠진다.
 *       {@code +1일} 은 SQL 이 아니라 여기서 계산해 바인딩한다</li>
 * </ul>
 *
 * <p>⚠️ 값의 <b>검증</b>(길이 · 코드 존재 · 기간 역전 → 400)은 여기서 하지 않는다 — 코드 판정에 DB 가 필요해
 * 서비스({@code ReportServiceImpl})가 한 곳에서 한다.
 */
@Getter
@ToString
public final class ReportListFilter {

    /** 필터 없음 — 종전과 같은 결과(하위 호환). 대시보드·테스트 등 필터를 모르는 호출부용 */
    public static final ReportListFilter NONE = new ReportListFilter(null, null, null, null);

    /** 보고서명 부분 일치어 (trim 후, 빈 값이면 null) — {@code position(keyword in rptp_nm) > 0} */
    private final String keyword;

    /** 출처 = 분석실행유형코드 ({@code ANLS_TYPE_CD}). 이름(srcNm)이 아니라 코드로 건다 */
    private final String anlsTypeCd;

    /** 생성일 하한 (포함) — {@code fromYmd} 00:00 */
    private final LocalDateTime fromDt;

    /** 생성일 상한 (미포함) — {@code toYmd + 1일} 00:00 */
    private final LocalDateTime toDtExclusive;

    private ReportListFilter(String keyword, String anlsTypeCd, LocalDateTime fromDt, LocalDateTime toDtExclusive) {
        this.keyword = keyword;
        this.anlsTypeCd = anlsTypeCd;
        this.fromDt = fromDt;
        this.toDtExclusive = toDtExclusive;
    }

    /**
     * 요청 파라미터를 정규화해 필터를 만든다. 모든 인자는 {@code null} 허용(미적용).
     *
     * @param keyword    보고서명 검색어
     * @param anlsTypeCd 출처 코드
     * @param fromYmd    생성일 시작(포함)
     * @param toYmd      생성일 종료(포함 — 내부적으로 다음 날 00:00 미만으로 바꾼다)
     */
    public static ReportListFilter of(String keyword, String anlsTypeCd, LocalDate fromYmd, LocalDate toYmd) {
        return new ReportListFilter(
                blankToNull(keyword),
                blankToNull(anlsTypeCd),
                fromYmd == null ? null : fromYmd.atStartOfDay(),
                toYmd == null ? null : toYmd.plusDays(1).atStartOfDay());
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
