package jeju.datahub.batch.cvlcpt.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * DHubManager 질의 문자열 조립의 안전장치 (Step 10-4).
 *
 * <p>이 API 는 <b>ClickHouse SQL 을 문자열로 그대로 받는다</b>({@code QueryRequest.query}).
 * 바인딩 파라미터가 없으므로 <b>주입 위험이 실재</b>한다 — 값을 문자열에 이어 붙이기 전에
 * 반드시 이 클래스를 거친다.
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li><b>일자</b>는 문자열을 그대로 넣지 않는다. {@link LocalDate} 로 파싱한 뒤
 *       {@code yyyy-MM-dd} 로 <b>다시 포맷한 값</b>만 조립에 쓴다 — 파싱을 통과한 값은
 *       숫자와 하이픈뿐이라 따옴표를 깨뜨릴 수 없다.</li>
 *   <li><b>식별자</b>(테이블·컬럼명)는 화이트리스트 정규식을 통과해야 한다. 백틱을 값에 넣어
 *       인용을 탈출하는 경로를 막는다.</li>
 *   <li><b>페이지 크기</b>는 숫자 타입으로 받고 범위를 검증한다.</li>
 *   <li>HTTP 파라미터 등 <b>외부 입력을 쿼리 문자열에 직접 넣지 않는다.</b> Job 파라미터로 들어오는
 *       {@code targetDate} 조차 {@link #date(String)} 를 거친다.</li>
 * </ul>
 */
public final class DhubSql {

    /**
     * 허용 식별자 — 백틱으로 감싼 {@code `db`.`table`} 또는 맨 이름 {@code cvlcpt_id}.
     * 영문·숫자·밑줄만 허용하므로 백틱/따옴표/공백/세미콜론이 값에 섞이면 통과하지 못한다.
     */
    private static final Pattern IDENTIFIER =
            Pattern.compile("(`[A-Za-z0-9_]+`|[A-Za-z_][A-Za-z0-9_]*)(\\.(`[A-Za-z0-9_]+`|[A-Za-z_][A-Za-z0-9_]*))*");

    /**
     * 한 번에 요청할 수 있는 최대 행 수 — <b>{@code /api/v1/analytics/query} 의 서버측 상한</b>이다.
     * OpenAPI 스펙({@code AnalyticsQueryRequest.max_rows}) 원문: "Rows returned, default 100,
     * capped at 1000". 이 값을 넘겨 보내면 서버가 422 로 거절하므로 <b>여기서 먼저 막는다.</b>
     *
     * <p>envoy 게이트웨이가 응답을 전부 버퍼링해 넘기는 사정(사전조사 C)도 그대로다 — 1건 ≈ 1KB
     * 기준 1,000행이면 단일 응답 약 1.1MB 로 실측 130~153ms 다.
     */
    static final int MAX_PAGE_SIZE = 1_000;

    private DhubSql() {
    }

    /**
     * 테이블/컬럼 식별자를 검증해 그대로 돌려준다.
     *
     * @throws IllegalArgumentException 비었거나 허용 문자 밖의 값이 섞여 있을 때
     */
    public static String table(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "원천 테이블이 비어 있습니다. 프로퍼티 dhub.source-table 을 설정하세요(하드코딩 금지).");
        }
        String trimmed = raw.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "SQL 식별자로 쓸 수 없는 값입니다(영문·숫자·밑줄과 백틱 인용만 허용): " + trimmed);
        }
        return trimmed;
    }

    /** {@link #table(String)} 과 같은 검증. 컬럼명을 조립할 때 의도를 드러내려고 이름만 나눠 둔다. */
    public static String column(String raw) {
        return table(raw);
    }

    /**
     * 문자열 일자를 {@link LocalDate} 로 파싱한다. <b>조립에 쓰는 모든 일자는 이 관문을 통과한다.</b>
     *
     * @throws IllegalArgumentException {@code yyyy-MM-dd} 가 아닐 때
     */
    public static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("일자가 비어 있습니다(형식: yyyy-MM-dd).");
        }
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("일자 형식이 잘못됐습니다(yyyy-MM-dd 여야 함): " + raw, e);
        }
    }

    /** 파싱된 일자를 SQL 리터럴 {@code 'yyyy-MM-dd'} 로 만든다. */
    public static String dateLiteral(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("일자가 null 입니다.");
        }
        return "'" + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "'";
    }

    /**
     * 반개구간 술어 {@code col >= 'from' AND col < 'toExclusive'} 를 만든다.
     *
     * <p><b>반개구간으로 통일하는 이유</b>: {@code dclr_ymd} 가 DATE 든 DATETIME 이든 같은 술어가
     * 맞아떨어진다. {@code = '{일자}'} 는 DATETIME 이면 시각 때문에 빗나간다.
     *
     * @throws IllegalArgumentException 구간이 비어 있거나 뒤집혔을 때
     */
    public static String halfOpenRange(String column, LocalDate from, LocalDate toExclusive) {
        String col = column(column);
        if (from == null || toExclusive == null) {
            throw new IllegalArgumentException("조회 구간이 비어 있습니다. from=" + from + " to=" + toExclusive);
        }
        if (!from.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "조회 구간이 비었거나 뒤집혔습니다(반개구간은 from < to 여야 함): [" + from + ", " + toExclusive + ")");
        }
        return col + " >= " + dateLiteral(from) + " AND " + col + " < " + dateLiteral(toExclusive);
    }

    /**
     * 하한만 있는 술어 {@code col >= 'from'} — 끝이 열린 구간(증분 수집 "그 날부터 이후 전부", TSK-4316).
     * 상한이 있으면 {@link #halfOpenRange} 를 쓴다.
     *
     * @throws IllegalArgumentException 일자가 비어 있을 때
     */
    public static String atLeast(String column, LocalDate from) {
        String col = column(column);
        return col + " >= " + dateLiteral(from);
    }

    /**
     * 페이지 크기를 검증한다. 숫자 타입으로만 받으므로 문자열 주입 경로 자체가 없고,
     * 여기서는 범위만 본다.
     */
    public static int limit(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "페이지 크기는 1 ~ " + MAX_PAGE_SIZE + " 여야 합니다(dhub.page-size): " + pageSize);
        }
        return pageSize;
    }

    /**
     * keyset 페이징의 커서 값을 SQL 문자열 리터럴로 만든다.
     *
     * <p>커서는 <b>직전 페이지 마지막 행의 {@code cvlcpt_id}</b> 이므로 외부 입력이 아니라 API 응답
     * 값이다. 그래도 원천 값이 그대로 SQL 에 들어가는 유일한 자리라 형식을 검증한다
     * ({@code SPP-2501-0103787} 형태 — 영문·숫자·하이픈·밑줄).
     */
    public static String stringLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("문자열 리터럴이 비어 있습니다.");
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "SQL 문자열 리터럴로 쓸 수 없는 값입니다(영문·숫자·하이픈·밑줄만 허용): " + trimmed);
        }
        return "'" + trimmed + "'";
    }
}
