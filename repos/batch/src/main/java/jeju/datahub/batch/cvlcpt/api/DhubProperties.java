package jeju.datahub.batch.cvlcpt.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DHubManager 수집 API 설정 ({@code dhub.*}).
 *
 * <p>{@code base-url}·{@code table-id}·{@code source-table} 은 <b>절대 소스에 하드코딩하지 않는다</b>
 * — 원천 테이블 이름에는 스냅샷 타임스탬프가 박혀 있어 갱신 때마다 바뀌고, 데이터셋 UUID 도
 * 환경마다 다르다. 실제 값은 전부 {@code application.yml} 의 {@code dhub.*} 에 있다.
 *
 * <p><b>자격증명은 환경변수로만 주입한다</b>({@code DHUB_USER_EMAIL} / {@code DHUB_USER_PW}, 루트
 * {@code .env} → {@code local.sh} 가 export). 기본값을 빈 문자열로 둬 키 없이도 기동은 되게 하고
 * ({@code geocode.vworld.api-key} 와 같은 관례 — 기동을 죽이면 헬스체크·트리거 엔드포인트까지 함께
 * 죽는다), 실제 검증은 Job 시작 시점의 {@link #requireCredentials()} 가 한다.
 *
 * <p>⚠️ 프로퍼티 이름은 {@code DHUB_EMAIL}/{@code DHUB_PASSWORD} 가 아니라
 * <b>{@code DHUB_USER_EMAIL}/{@code DHUB_USER_PW}</b> 다(2026-08-04 정정).
 *
 * @see DhubAuthClient
 * @see DhubQueryClient
 */
@Component
@ConfigurationProperties(prefix = "dhub")
public class DhubProperties {

    /** 조회 모드 — 평시 일일 수집(daily)과 최초 전량 적재(backfill)를 구분한다. */
    public enum QueryMode {
        /** 원천 load_dt 최대값부터 실행일까지 증분 수집. */
        INCREMENTAL,
        /** 평시: 전일 하루치만 수집한다(02:00 스케줄 기준 하루가 완결된 뒤). */
        DAILY,
        /** 최초 1회: {@code backfill-from} ~ {@code backfill-to} 반개구간 전량. */
        BACKFILL
    }

    /** 일일 수집 기준 시간대 — "전일"은 실행 서버 로캘이 아니라 항상 한국 기준으로 정한다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private String baseUrl;
    private String email = "";
    private String password = "";
    private String tableId;
    private String sourceTable;
    private QueryMode queryMode = QueryMode.INCREMENTAL;
    private LocalDate backfillFrom;
    /** 반개구간 끝(미포함). 비우면 실행일 — {@link #resolveBackfillTo(Clock)} 참조. */
    private LocalDate backfillTo;
    private int pageSize = 1000;
    private int timeoutMs = 30_000;

    // ─────────────────────────────────────────────────────────────────────
    // pblpklt(공영주차장) 수집용 — 민원·단속과 같은 POST /api/v1/analytics/query + pklt_id keyset 페이징
    // (🔁 2026-09-17 — 종전 GET /datasets/{id}/table 덤프에서 전환).
    //
    // pblpklt 는 일자 컬럼이 없어 daily/backfill 모드가 의미 없으므로 별도 키 두지 않는다.
    // 🔴 키 pklt_id 는 문자열 — 사전순(LEXICOGRAPHIC)으로만 페이징한다(숫자 캐스팅 금지, DhubPblpkltApiClient).
    // ─────────────────────────────────────────────────────────────────────
    /**
     * pblpklt 데이터셋 UUID(예: dataset.95c563b4-2466-4805-b623-3f336645fc7d) — analytics/query 의 {@code source_id}.
     * 🔴 {@link #pblpkltSourceTable} 와 <b>같은 데이터셋의 짝</b>이어야 한다.
     */
    private String pblpkltTableId;
    /**
     * pblpklt 원천 테이블(예: `jeju_mini`.`tb_pblpklt`) — 수집 SQL 과 총건수 교차검증 SQL <b>양쪽의 FROM 절</b>이다
     * (🔁 2026-09-17 전환 전에는 교차검증에만 쓰였다). 🔴 {@link #pblpkltTableId} 와 <b>짝</b>이다 —
     * 어긋나면 analytics/query 가 422 로 거절해 수집이 첫 페이지부터 실패한다.
     */
    private String pblpkltSourceTable;
    /**
     * keyset <b>페이지 크기</b> — 한 번의 analytics/query 가 받는 행 수({@code LIMIT} = {@code max_rows}).
     *
     * <p>🔁 2026-09-17 — 의미가 <b>"전량 여부를 가르는 limit(0 = 쿼리스트링 limit 없이 전량)" → "페이지 크기"</b>
     * 로 바뀌었다. 기본값 0 → <b>1000</b>(서버 {@code max_rows} 상한 {@code le:1000}). 허용 범위는 <b>1..1000</b> 이고
     * 벗어나면 Job 시작 시점(HTTP 호출 전)에 {@link #requirePblpkltPageSize()} 가 실패시킨다.
     * 🔴 {@code 0} 을 "무제한" 으로 보정하지 않는다 — {@code LIMIT 0} 으로 0행을 받고 성공한 채 소멸 마킹이 돌면
     * 전량 소멸이다(Reader 의 총건수 가드·빈 seen set 가드가 막지만, 설정 단계에서 먼저 끊는다).
     * 전량 수집은 페이징과 총건수 교차검증이 보장한다(원천 2,272행 = 1000 기준 3회 왕복).
     */
    private int pblpkltPageSize = DhubSql.MAX_PAGE_SIZE;

    private KeysetOrder pblpkltKeysetOrder = KeysetOrder.LEXICOGRAPHIC;
    private String utztnCsvPath = "";
    private String sftychckCsvPath = "";
    private java.util.Set<Long> sftychckCsvIncludeIds = java.util.Set.of();
    public java.util.Set<Long> getSftychckCsvIncludeIds() { return sftychckCsvIncludeIds; }
    public void setSftychckCsvIncludeIds(java.util.Set<Long> ids) { sftychckCsvIncludeIds = ids; }
    public KeysetOrder getPblpkltKeysetOrder() { return pblpkltKeysetOrder; }
    public void setPblpkltKeysetOrder(KeysetOrder value) { pblpkltKeysetOrder = value; }
    public String getUtztnCsvPath() { return utztnCsvPath; }
    public void setUtztnCsvPath(String value) { utztnCsvPath = value; }
    public String getSftychckCsvPath() { return sftychckCsvPath; }
    public void setSftychckCsvPath(String value) { sftychckCsvPath = value; }

    // ─────────────────────────────────────────────────────────────────────
    // pklt-utztn(주차장 이용패턴) 수집용 — POST /api/v1/analytics/query
    //
    // 🔴 keyset 페이징이 아니다 — 원천에 유일한 단일 컬럼이 없다(행을 가리는 것은
    //    (pklt_nm, msmt_ym) 복합). 그래서 msmt_ym 파티션 순회로 읽는다(DhubPkltUtztnItemReader).
    // ─────────────────────────────────────────────────────────────────────
    /**
     * pklt-utztn 데이터셋 UUID(예: dataset.23af2356-7668-4e03-8ca4-9d0242cbb1b9) — analytics/query 의 {@code source_id}.
     * 🔴 {@link #utztnSourceTable} 와 <b>같은 데이터셋의 짝</b>이어야 한다 — 어긋나면 422 다.
     */
    private String utztnTableId;
    /**
     * pklt-utztn 원천 테이블(예: `jeju_mini`.`tb_pblpklt_use_cnt`) — 월 목록·수집·총건수 SQL <b>모두의 FROM 절</b>이다.
     * 🔴 {@link #utztnTableId} 와 <b>짝</b>이다.
     */
    private String utztnSourceTable;
    /**
     * 한 달 질의의 <b>{@code max_rows} 상한</b>이다 — 형제 키(*-page-size)와 달리 "페이지 크기" 가 아니다.
     *
     * <p>월 최대 369행(주차장 수)이라 한 왕복에 들어간다. 🔴 반환 행수가 이 값에 <b>닿으면</b> 그 달이
     * 잘렸다는 뜻이므로 FAILED 로 끊는다 — 판정 기준을 {@code 1000} 리터럴로 박지 않고 이 값을 쓴다.
     * 허용 범위는 <b>1..1000</b> 이고 벗어나면 Job 시작 시점에 {@link #requireUtztnMaxRows()} 가 실패시킨다
     * ({@code 0} 은 {@code LIMIT 0} = 0행 성공이라 거부한다).
     */
    private int utztnMaxRows = DhubSql.MAX_PAGE_SIZE;

    // ─────────────────────────────────────────────────────────────────────
    // crackdown(단속) 수집용 — 민원과 같은 POST /api/v1/analytics/query + keyset 페이징
    // (🔁 2026-09-17 — 종전 GET /datasets/{id}/table 덤프에서 전환).
    //
    // 원천 테이블은 tb_crdn_dsctn(2026-09-14 표준 이름으로 변경). 갱신 시 UUID·테이블명이
    // 바뀔 수 있다 — 그래서 전부 설정으로 뺀다. 일자 필터 없이 전량 수집(수동 트리거 전용).
    // ─────────────────────────────────────────────────────────────────────
    /**
     * crackdown 데이터셋 UUID(예: dataset.2ee0d04b-ff2a-4245-9848-ec6a7e49ac00, 2026-08-28 갱신) —
     * analytics/query 의 {@code source_id}. 🔴 {@link #crackdownSourceTable} 과 <b>같은 데이터셋의 짝</b>이어야 한다.
     */
    private String crackdownTableId;
    /**
     * crackdown 원천 테이블(예: `jeju_smart_city_test`.`tb_crdn_dsctn`) — 수집 SQL 과 총건수 교차검증 SQL
     * <b>양쪽의 FROM 절</b>이다(🔁 2026-09-17 전환 전에는 교차검증에만 쓰였다). 🔴 {@link #crackdownTableId} 와
     * 짝이 어긋나면 analytics/query 가 422 로 거절해 수집이 첫 페이지부터 실패한다.
     * 실제 값은 {@code application.yml} 이 주며, 기본값은 빈 값 — 검증은 {@link #requireCrackdownSourceTable()}.
     */
    private String crackdownSourceTable = "";
    /**
     * keyset <b>페이지 크기</b> — 한 번의 analytics/query 가 받는 행 수({@code LIMIT} = {@code max_rows}).
     *
     * <p>🔁 2026-09-17 — 의미가 <b>"전량 여부를 가르는 limit(0 = 무제한)" → "페이지 크기"</b>
     * 로 바뀌었다. 기본값 0 → <b>1000</b>(서버 {@code max_rows} 상한 {@code le:1000}). 허용 범위는
     * <b>1..1000</b> 이고 벗어나면 Job 시작 시점에 {@link #requireCrackdownPageSize()} 가 실패시킨다.
     * 🔴 {@code 0} 을 "무제한" 으로 보정하지 않는다 — 그대로 두면 {@code LIMIT 0} 으로 0행을 받고 성공한다.
     */
    private int crackdownPageSize = DhubSql.MAX_PAGE_SIZE;
    /**
     * 원천 {@code crdn_dt} 의 날짜 포맷. 현행 원천은 ISO 형태 {@code yyyy-MM-dd HH:mm:ss.SSS}
     * (예: `2025-01-01 00:07:00.000`, 전 행 23자)로 주며, {@code [.SSS]} 는 선택 구간이라 밀리초
     * 유무 모두 수용한다. 원천 포맷이 또 바뀌면 <b>코드 변경 없이</b> 설정으로 바로잡기 위해 노출한다.
     * 🔁 2026-11-13 — 새 원천은 마이크로초 6자리({@code 2025-01-30 17:52:13.000000})라 {@code [.SSSSSS][.SSS]}
     * 로 넓혔다(6자리·3자리·없음 모두). 해시는 원문으로 만들어 패턴 변경과 무관하다.
     * 기본값은 {@code application.yml} 의 값과 같게 맞춘다(혼동 방지).
     */
    private String crackdownDatePattern = "yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS]";

    // ─────────────────────────────────────────────────────────────────────
    // 주차장 안전점검 수집용 — POST /api/v1/analytics/query + keyset 페이징
    //
    // 🔴 sfty_chck_id 는 keyset 커서로만 쓰고 적재하지 않는다(우리 PK 는 IDENTITY — D8).
    // ─────────────────────────────────────────────────────────────────────
    /**
     * 주차장 안전점검 데이터셋 UUID(예: dataset.38d1553a-ffd2-4f39-a073-aafdd049a411) — analytics/query 의 {@code source_id}.
     * 🔴 {@link #sftychckSourceTable} 과 <b>같은 데이터셋의 짝</b>이어야 한다 — 어긋나면 422 다.
     */
    private String sftychckTableId;
    /**
     * 주차장 안전점검 원천 테이블(예: `jeju_mini`.`tb_pklt_sfty_chck`) — 수집 SQL 과 총건수 교차검증 SQL
     * <b>양쪽의 FROM 절</b>이다. 🔴 {@link #sftychckTableId} 와 <b>짝</b>이다.
     * 실제 값은 {@code application.yml} 이 주며, 기본값은 빈 값 — 검증은 {@link #requireSftychckSourceTable()}.
     */
    private String sftychckSourceTable = "";
    /**
     * keyset <b>페이지 크기</b> — 한 번의 analytics/query 가 받는 행 수({@code LIMIT} = {@code max_rows}).
     * 허용 범위는 <b>1..1000</b> 이고 벗어나면 Job 시작 시점에 {@link #requireSftychckPageSize()} 가 실패시킨다.
     * 🔴 {@code 0} 은 {@code LIMIT 0} = 0행 성공이라 거부한다. 전량 수집은 페이징이 보장한다.
     */
    private int sftychckPageSize = DhubSql.MAX_PAGE_SIZE;

    /**
     * "오늘"을 덮어쓰는 값(`yyyy-MM-dd`). 비어 있으면 <b>실제 시스템 날짜</b>를 쓴다.
     *
     * <p><b>왜 필요한가 —</b> 원천이 <b>2025-01-01 ~ 2026-05-31 정적 스냅샷</b>이라(실측)
     * "어제"를 조회하면 매번 0건이다. 그래서 daily 경로를 검증할 수 없다. 이 값을 예컨대
     * {@code 2026-06-01} 로 주면 daily 가 {@code 2026-05-31}(345건)을 읽어 <b>실제 데이터로</b>
     * 전 구간이 돈다.
     *
     * <p><b>임시 수단이다.</b> 원천이 실시간으로 갱신되기 시작하면 이 값을 비우면 되고, 그러면
     * 코드 변경 없이 실제 날짜 기준으로 돌아간다(리스크 34).
     *
     * <p>주입: 환경변수 {@code BATCH_TODAY} → 루트 {@code .env} → {@code local.sh} 가 export.
     * <b>문자열로 받아 {@link #resolveToday(Clock)} 에서 파싱한다</b> — {@code ${BATCH_TODAY:}} 의
     * 빈 문자열이 {@code LocalDate} 바인딩에서 터지지 않게 하고, 형식이 틀렸을 때 원인이 분명한
     * 예외를 주기 위함이다.
     */
    private String todayOverride = "";

    /**
     * 자격증명이 실제로 주입됐는지 확인한다. <b>Job 시작 시점에 부른다</b> — 값이 없으면 조용히
     * 진행하지 않고 원인이 분명한 예외로 즉시 실패시킨다.
     *
     * @throws IllegalStateException 이메일 또는 비밀번호가 비어 있을 때. <b>메시지에 값을 싣지 않는다.</b>
     */
    public void requireCredentials() {
        if (isBlank(email) || isBlank(password)) {
            throw new IllegalStateException(
                    "DHubManager 자격증명이 없습니다. 환경변수 DHUB_USER_EMAIL / DHUB_USER_PW 를 주입하세요 "
                            + "(루트 .env → local.sh 가 export). 프로퍼티: dhub.email / dhub.password");
        }
    }

    /**
     * 조회 대상 테이블 식별자를 검증해 돌려준다. SQL 문자열로 조립되므로
     * {@link DhubSql#table(String)} 의 화이트리스트를 통과해야 한다(10-4).
     */
    public String requireSourceTable() {
        return DhubSql.table(sourceTable);
    }

    /**
     * crackdown 페이지 크기를 검증해 돌려준다. <b>Job 시작 시점에 부른다</b>(기동은 죽이지 않는다).
     *
     * @throws IllegalStateException {@code 1..1000} 밖일 때 — 특히 종전 관례 {@code 0}(무제한)이 남아 있을 때
     */
    public int requireCrackdownPageSize() {
        if (crackdownPageSize < 1 || crackdownPageSize > DhubSql.MAX_PAGE_SIZE) {
            throw new IllegalStateException(
                    "dhub.crackdown-page-size 는 1 ~ " + DhubSql.MAX_PAGE_SIZE + " 인 페이지 크기여야 합니다: "
                            + crackdownPageSize + " — 🔁 2026-09-17 부터 0(무제한) 관례는 없습니다"
                            + "(LIMIT 0 은 0행 성공이 된다). 전량 수집은 페이징이 보장한다.");
        }
        return crackdownPageSize;
    }

    /**
     * pblpklt 페이지 크기를 검증해 돌려준다. <b>Job 시작 시점에 부른다</b>(기동은 죽이지 않는다) — 🔁 2026-09-17.
     *
     * @throws IllegalStateException {@code 1..1000} 밖일 때 — 특히 종전 관례 {@code 0}(전량 덤프)이 남아 있을 때
     */
    public int requirePblpkltPageSize() {
        if (pblpkltPageSize < 1 || pblpkltPageSize > DhubSql.MAX_PAGE_SIZE) {
            throw new IllegalStateException(
                    "dhub.pblpklt-page-size 는 1 ~ " + DhubSql.MAX_PAGE_SIZE + " 인 페이지 크기여야 합니다: "
                            + pblpkltPageSize + " — 🔁 2026-09-17 부터 0(전량 덤프) 관례는 없습니다"
                            + "(LIMIT 0 은 0행 성공이 된다). 전량 수집은 페이징이 보장한다.");
        }
        return pblpkltPageSize;
    }

    /**
     * crackdown 원천 테이블 식별자를 검증해 돌려준다. <b>Job 시작 시점에 부른다</b> — 스파이크로
     * 확정 전 기본값이 빈 문자열이라, 값 없이 실행하면 조용히 빈 SQL 을 만들지 않고 여기서
     * 원인이 분명한 예외로 즉시 실패한다. 화이트리스트는 {@link DhubSql#table(String)} 과 동일.
     */
    public String requireCrackdownSourceTable() {
        if (isBlank(crackdownSourceTable)) {
            throw new IllegalStateException(
                    "crackdown 원천 테이블이 비어 있습니다. 데이터셋 상세 조회(GET /api/v1/datasets/{id})의 "
                            + "item 값으로 프로퍼티 dhub.crackdown-source-table 을 설정하세요(하드코딩 금지).");
        }
        return DhubSql.table(crackdownSourceTable);
    }

    /**
     * pblpklt 원천 테이블 식별자를 검증해 돌려준다 (🔁 2026-09-17 신설).
     *
     * <p>🔴 <b>이 값은 2026-09-17 부터 실제 질의에 들어간다</b> — {@code POST /api/v1/analytics/query} 의
     * {@code FROM} 절이다(처음엔 총건수 교차검증만, 이후 <b>수집 SQL 도</b>). 그 전에는 로그·식별자
     * 검증용으로만 쓰여 틀린 값이어도 증상이 없었다(당시 GET 테이블 덤프는 본문 SQL 을 무시했다).
     * 틀리면 그 엔드포인트가 <b>422 로 허용 테이블 이름을 알려주므로</b> 그것으로 교정한다.
     * 화이트리스트는 {@link DhubSql#table(String)} 과 동일.
     */
    public String requirePblpkltSourceTable() {
        if (isBlank(pblpkltSourceTable)) {
            throw new IllegalStateException(
                    "pblpklt 원천 테이블이 비어 있습니다. 프로퍼티 dhub.pblpklt-source-table 을 실제 "
                            + "테이블 이름으로 설정하세요(하드코딩 금지). 이름을 모르면 analytics/query 에 "
                            + "틀린 이름으로 질의하면 422 응답이 허용 이름을 알려줍니다.");
        }
        return DhubSql.table(pblpkltSourceTable);
    }

    /**
     * pklt-utztn 의 {@code max_rows} 를 검증해 돌려준다. <b>Job 시작 시점에 부른다</b>(기동은 죽이지 않는다).
     *
     * @throws IllegalStateException {@code 1..1000} 밖일 때 — 특히 {@code 0}(LIMIT 0 = 0행 성공)일 때
     */
    public int requireUtztnMaxRows() {
        if (utztnMaxRows < 1 || utztnMaxRows > DhubSql.MAX_PAGE_SIZE) {
            throw new IllegalStateException(
                    "dhub.utztn-max-rows 는 1 ~ " + DhubSql.MAX_PAGE_SIZE + " 여야 합니다: " + utztnMaxRows
                            + " — 0 은 LIMIT 0(0행 성공)이 되어 거부합니다. 이 값은 페이지 크기가 아니라 "
                            + "한 달 질의의 max_rows 상한이며, 반환 행수가 닿으면 그 달이 잘린 것으로 보고 FAILED 로 끊습니다.");
        }
        return utztnMaxRows;
    }

    /**
     * pklt-utztn 원천 테이블 식별자를 검증해 돌려준다. <b>Job 시작 시점에 부른다.</b>
     * {@link #utztnTableId} 와 짝이 어긋나면 analytics/query 가 422 로 허용 이름을 알려준다.
     * 화이트리스트는 {@link DhubSql#table(String)} 과 동일.
     */
    public String requireUtztnSourceTable() {
        if (isBlank(utztnSourceTable)) {
            throw new IllegalStateException(
                    "pklt-utztn 원천 테이블이 비어 있습니다. 프로퍼티 dhub.utztn-source-table 을 실제 "
                            + "테이블 이름으로 설정하세요(하드코딩 금지). 이름을 모르면 analytics/query 에 "
                            + "틀린 이름으로 질의하면 422 응답이 허용 이름을 알려줍니다.");
        }
        return DhubSql.table(utztnSourceTable);
    }

    /**
     * 주차장 안전점검 페이지 크기를 검증해 돌려준다. <b>Job 시작 시점에 부른다</b>(기동은 죽이지 않는다).
     *
     * @throws IllegalStateException {@code 1..1000} 밖일 때 — 특히 {@code 0}(LIMIT 0 = 0행 성공)일 때
     */
    public int requireSftychckPageSize() {
        if (sftychckPageSize < 1 || sftychckPageSize > DhubSql.MAX_PAGE_SIZE) {
            throw new IllegalStateException(
                    "dhub.sftychck-page-size 는 1 ~ " + DhubSql.MAX_PAGE_SIZE + " 인 페이지 크기여야 합니다: "
                            + sftychckPageSize + " — 0 은 LIMIT 0(0행 성공)이 되어 거부합니다. "
                            + "전량 수집은 페이징이 보장한다.");
        }
        return sftychckPageSize;
    }

    /**
     * 주차장 안전점검 원천 테이블 식별자를 검증해 돌려준다. <b>Job 시작 시점에 부른다.</b>
     * {@link #sftychckTableId} 와 짝이 어긋나면 analytics/query 가 422 로 허용 이름을 알려준다.
     * 화이트리스트는 {@link DhubSql#table(String)} 과 동일.
     */
    public String requireSftychckSourceTable() {
        if (isBlank(sftychckSourceTable)) {
            throw new IllegalStateException(
                    "주차장 안전점검 원천 테이블이 비어 있습니다. 프로퍼티 dhub.sftychck-source-table 을 실제 "
                            + "테이블 이름으로 설정하세요(하드코딩 금지). 이름을 모르면 analytics/query 에 "
                            + "틀린 이름으로 질의하면 422 응답이 허용 이름을 알려줍니다.");
        }
        return DhubSql.table(sftychckSourceTable);
    }

    /**
     * backfill 반개구간의 끝(미포함)을 정한다. 설정이 비어 있으면 <b>실행일</b>이다 —
     * 연 단위로 고정하면 원천이 2025.1~2026.5 라 2026년분이 통째로 누락된다.
     */
    public LocalDate resolveBackfillTo(Clock clock) {
        return backfillTo != null ? backfillTo : resolveToday(clock);
    }

    /** daily 모드의 기준일 = <b>전일</b>(Asia/Seoul). 02:00 실행 시 당일분만 보면 나머지가 영구 누락된다. */
    public LocalDate resolveDailyDate(Clock clock) {
        return resolveToday(clock).minusDays(1);
    }

    /**
     * 이 배치가 "오늘"로 보는 날짜. {@code dhub.today-override}(환경변수 {@code BATCH_TODAY})가
     * 있으면 그 값, 없으면 <b>실제 시스템 날짜</b>(Asia/Seoul)다.
     *
     * <p>원천이 정적 스냅샷이라 실제 "어제"가 늘 0건인 동안 daily 경로를 실데이터로 검증하기 위한
     * 임시 수단이다({@link #todayOverride} 참조). 값을 비우면 즉시 실제 날짜로 돌아간다.
     *
     * @throws IllegalStateException 형식이 {@code yyyy-MM-dd} 가 아닐 때. 조용히 실제 날짜로
     *         넘어가면 <b>의도한 날짜가 아닌 구간을 수집하고도 정상으로 보인다.</b>
     */
    public LocalDate resolveToday(Clock clock) {
        if (isBlank(todayOverride)) {
            return LocalDate.now(clock.withZone(ZONE));
        }
        try {
            // 10-4 와 같은 규율 — 문자열을 그대로 쓰지 않고 파싱해 정규화한다.
            return LocalDate.parse(todayOverride.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalStateException(
                    "BATCH_TODAY 형식이 잘못됐습니다(yyyy-MM-dd 여야 함): '" + todayOverride
                            + "'. 실제 날짜를 쓰려면 값을 비우세요. 프로퍼티: dhub.today-override", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public QueryMode getQueryMode() {
        return queryMode;
    }

    public void setQueryMode(QueryMode queryMode) {
        this.queryMode = queryMode;
    }

    public LocalDate getBackfillFrom() {
        return backfillFrom;
    }

    public void setBackfillFrom(LocalDate backfillFrom) {
        this.backfillFrom = backfillFrom;
    }

    public LocalDate getBackfillTo() {
        return backfillTo;
    }

    public void setBackfillTo(LocalDate backfillTo) {
        this.backfillTo = backfillTo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getTodayOverride() {
        return todayOverride;
    }

    public void setTodayOverride(String todayOverride) {
        this.todayOverride = todayOverride;
    }

    public String getPblpkltTableId() {
        return pblpkltTableId;
    }

    public void setPblpkltTableId(String pblpkltTableId) {
        this.pblpkltTableId = pblpkltTableId;
    }

    public String getPblpkltSourceTable() {
        return pblpkltSourceTable;
    }

    public void setPblpkltSourceTable(String pblpkltSourceTable) {
        this.pblpkltSourceTable = pblpkltSourceTable;
    }

    public int getPblpkltPageSize() {
        return pblpkltPageSize;
    }

    public void setPblpkltPageSize(int pblpkltPageSize) {
        this.pblpkltPageSize = pblpkltPageSize;
    }

    public String getUtztnTableId() {
        return utztnTableId;
    }

    public void setUtztnTableId(String utztnTableId) {
        this.utztnTableId = utztnTableId;
    }

    public String getUtztnSourceTable() {
        return utztnSourceTable;
    }

    public void setUtztnSourceTable(String utztnSourceTable) {
        this.utztnSourceTable = utztnSourceTable;
    }

    public int getUtztnMaxRows() {
        return utztnMaxRows;
    }

    public void setUtztnMaxRows(int utztnMaxRows) {
        this.utztnMaxRows = utztnMaxRows;
    }

    public String getCrackdownTableId() {
        return crackdownTableId;
    }

    public void setCrackdownTableId(String crackdownTableId) {
        this.crackdownTableId = crackdownTableId;
    }

    public String getCrackdownSourceTable() {
        return crackdownSourceTable;
    }

    public void setCrackdownSourceTable(String crackdownSourceTable) {
        this.crackdownSourceTable = crackdownSourceTable;
    }

    public int getCrackdownPageSize() {
        return crackdownPageSize;
    }

    public void setCrackdownPageSize(int crackdownPageSize) {
        this.crackdownPageSize = crackdownPageSize;
    }

    public String getCrackdownDatePattern() {
        return crackdownDatePattern;
    }

    public void setCrackdownDatePattern(String crackdownDatePattern) {
        this.crackdownDatePattern = crackdownDatePattern;
    }

    public String getSftychckTableId() {
        return sftychckTableId;
    }

    public void setSftychckTableId(String sftychckTableId) {
        this.sftychckTableId = sftychckTableId;
    }

    public String getSftychckSourceTable() {
        return sftychckSourceTable;
    }

    public void setSftychckSourceTable(String sftychckSourceTable) {
        this.sftychckSourceTable = sftychckSourceTable;
    }

    public int getSftychckPageSize() {
        return sftychckPageSize;
    }

    public void setSftychckPageSize(int sftychckPageSize) {
        this.sftychckPageSize = sftychckPageSize;
    }

    /**
     * 로깅·진단용 요약. <b>{@code email}·{@code password} 는 절대 넣지 않는다.</b>
     *
     * <p>{@code todayOverride} 는 <b>일부러 넣는다</b> — 값이 설정돼 있으면 수집 구간이 실제 날짜와
     * 달라지므로, 기동 로그만 보고도 "왜 어제가 그 날짜인지" 알 수 있어야 한다.
     */
    @Override
    public String toString() {
        // ⚠️ 전체를 괄호로 묶는다 — `+` 보다 메서드 호출이 먼저라, 괄호 없이는 .formatted 가
        //    마지막 리터럴에만 적용돼 %d 자리에 String 이 들어가 IllegalFormatConversionException 이 난다.
        return ("DhubProperties{baseUrl=%s, cvlcpt={tableId=%s, sourceTable=%s, queryMode=%s, backfill=[%s, %s)}, "
                + "pblpklt={tableId=%s, sourceTable=%s}, crackdown={tableId=%s, sourceTable=%s, datePattern=%s}, "
                + "pageSize=%d, pblpkltPageSize=%d, crackdownPageSize=%d, timeoutMs=%d, todayOverride=%s}")
                .formatted(baseUrl, tableId, sourceTable, queryMode, backfillFrom, backfillTo,
                        pblpkltTableId, pblpkltSourceTable,
                        crackdownTableId, crackdownSourceTable, crackdownDatePattern,
                        pageSize, pblpkltPageSize, crackdownPageSize, timeoutMs,
                        isBlank(todayOverride) ? "(실제 날짜)" : todayOverride);
    }
}
