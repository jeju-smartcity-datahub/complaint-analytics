package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 수집 API 응답 1건을 담는 record — Reader 가 내고 Processor 가 받는다.
 *
 * <h2>🔁 2026-08-24 — staging 이 없어졌다</h2>
 * 종전에는 이 타입이 {@code parking.tb_cvlcpt_origin} 1행으로 <b>그대로 적재</b>됐고, 그 뒤 별도
 * 가공 잡이 origin 을 다시 읽어 분류·지오코딩했다. 그 2단 구조가 {@code 47-CVLCPT-DIRECT.sql} 로
 * <b>1단으로 합쳐지면서</b> origin 테이블과 가공 잡의 입력 타입이 함께 사라졌다.
 * 이제 이 record 는 <b>DB 에 그대로 적재되지 않는다</b> — Processor 가 분류·지오코딩을 거쳐
 * {@code CvlcptRecord} 로 바꾼 뒤에야 {@code parking.tb_cvlcpt} 에 들어간다.
 *
 * <p>⚠️ 그래서 <b>원본 컬럼을 여기서 하나도 버리면 안 된다</b> — staging 이 없어 "API 재호출 없이
 * 재가공" 의 유일한 근거가 {@code tb_cvlcpt} 에 흡수된 원본 컬럼들이다.
 *
 * <h2>🔁 원천 이관 — {@code tb_cvlcpt} (2026-09-01, TSK-4109)</h2>
 * DHubManager 가 데이터셋 {@code dataset.25c829ec-…} 의 대상 테이블을 {@code minwon_sentiment} 에서
 * <b>{@code jeju_smart_city_test.tb_cvlcpt}</b> 로 갈아끼웠다(2026-08-31). 옛 테이블을 계속 조회하면
 * 서버가 <b>422 로 거절</b>해 Job 이 read 0 으로 즉시 FAILED 된다.
 *
 * <p>새 원천은 <b>우리 서비스 테이블 {@code parking.tb_cvlcpt} 와 컬럼 구성이 같다</b> —
 * 이름이 5쌍 바뀌었고, 우리가 만들던 가공 컬럼의 <b>자리</b>가 원천에 생겼다.
 *
 * <table>
 *   <caption>구 {@code minwon_sentiment} → 신 {@code tb_cvlcpt} 개명 5쌍</caption>
 *   <tr><th>구</th><th>신</th></tr>
 *   <tr><td>{@code dclr_no}</td><td><b>{@code cvlcpt_id}</b> (PK · keyset 커서)</td></tr>
 *   <tr><td>{@code dclr_ttl}</td><td>{@code ttl}</td></tr>
 *   <tr><td>{@code dclr_cn}</td><td>{@code cvlcpt_cn}</td></tr>
 *   <tr><td>{@code dclr_cn_sntmnt}</td><td>{@code aprs_cd} (값은 여전히 한글 원문)</td></tr>
 *   <tr><td>{@code dclr_at}</td><td><b>{@code ocrn_dt}</b> (조회 필터 키)</td></tr>
 * </table>
 *
 * <h2>🔴 가공 컬럼은 "원천이 주면 쓰고, 없으면 우리가 만든다"</h2>
 * 새 원천에는 {@code cvlcpt_type_cd}·{@code emd_cd}·{@code lat}/{@code lot}·{@code src_cd} 자리가
 * 있다. <b>2026-09-01 실측으로는 78,386행 전량 NULL</b> 이라 지금은 전부 우리가 계산하지만,
 * 원천이 언제든 채워 보낼 수 있으므로 <b>받아 두고</b> {@code CvlcptGeocodeProcessor} 가
 * "있으면 그대로, 없으면 계산" 으로 분기한다. 받지 않으면 원천이 채워 보내도 알아채지 못한 채
 * 지오코딩 쿼터를 계속 태운다.
 *
 * <p>⚠️ 이 record 의 이 필드들은 <b>원천이 준 값 그대로</b>이며 가공 결과가 아니다. 최종값을
 * 정하는 것은 Processor 다 — 여기서는 "왔다/안 왔다"만 전달한다.
 *
 * <h2>버리는 컬럼</h2>
 * <ul>
 *   <li>{@code geom} — {@code lat}/{@code lot} 에서 DB 가 생성하는 컬럼이다
 *       ({@code GENERATED ALWAYS AS}). 받아도 넣을 자리가 없다.</li>
 *   <li>{@code risk_lvl_cd} — 이 배치가 산출하지 않는다. Writer 가 리터럴 NULL 로 쓴다.</li>
 * </ul>
 *
 * <h2>🔴 NOT NULL 컬럼과 {@code -} placeholder</h2>
 * 아래 컬럼은 {@code NOT NULL} 이라 API 가 {@code null}/공백을 줘도 반드시 채워야 한다.
 * 이때 <b>빈 문자열이 아니라 {@value #PLACEHOLDER} 를 넣는다</b> — 빈 문자열은 "값이 없다"와
 * "빈 값이 왔다"를 구분할 수 없어 나중에 원천 품질 문제를 추적할 수 없게 된다.
 *
 * <p>대상: {@code ttl}, {@code cvlcpt_cn}, {@code prgrs_stts}, {@code ans_prcs_stts},
 * {@code dclr_ocrn_rgn}, {@code prcs_inst_nm}, <b>{@code prcs_inst_pic_nm}</b>, {@code dclr_type}.
 * {@code prcs_inst_cnt}(integer NOT NULL)만 숫자라 {@code 0} 을 쓴다.
 * (2026-09-01 실측: {@code prcs_inst_pic_nm} 395건 · {@code prcs_inst_nm} 394건이 비어 온다.)
 *
 * <p>{@code prcs_inst_pic_nm}(처리기관담당자)은 <b>개인정보</b>지만 원본 보존이 목적이라 수집한다.
 * 서비스 테이블 {@code parking.tb_cvlcpt} 에는 이 컬럼 자체가 없어 <b>최소 수집 원칙은 서비스
 * 계층에서 그대로 지켜진다</b>(계획서 11-1).
 *
 * <h2>🔴 값을 정규화하는 두 컬럼 — {@code ans_ymd} · {@code ocrn_dt}</h2>
 * 원본 보존이 원칙이지만 <b>DB 컬럼 타입에 맞지 않는 값은 담을 수 없다.</b>
 * <ul>
 *   <li>{@code ans_ymd} 는 원천이 <b>{@code 2025.5.7} 형태</b>로 준다. {@code char(10)} 에는
 *       들어가지만 Processor 가 {@code LocalDate.parse} 로 읽어 {@code load_dt} 를 만들므로
 *       <b>그대로 두면 전 행에서 터진다.</b> 그래서 여기서 {@code yyyy-MM-dd} 로 정규화한다.</li>
 *   <li>{@code ocrn_dt}({@code 2026-01-10 00:00:00.000}) → {@code TIMESTAMP} 로 파싱한다.</li>
 * </ul>
 * 두 값 모두 <b>파싱 실패는 {@code null}</b> 이다 — 수집을 실패시키면 정상 행까지 함께 못 들어온다.
 *
 * <p>원천 cvlcpt_id는 nullable 비유일 메타데이터다. 내부 PK는 Writer에서 DB 시퀀스로 생성한다.
 * 수집 구간/커서는 원천 load_dt와 조회 행 해시를 사용한다.
 */
public record ComplaintOriginRow(
        // 필드 순서 = API_COLUMNS 순서. 둘을 어긋나게 두지 말 것.
        String cvlcptId,        // cvlcpt_id            nullable 원천 ID, 내부 PK/커서가 아님
        String dclrYmd,         // dclr_ymd             신고일자(원문). 2025 구간만 담긴다
        LocalDateTime ocrnDt,   // ocrn_dt              발생일시 · 조회 필터 키
        String aplcntSe,        // aplcnt_se
        String dclrFld,         // dclr_fld
        String srcPath,         // src_path             srcCd 가 없을 때의 변환 입력
        String dclrOcrnRgn,     // dclr_ocrn_rgn        좌표가 없을 때의 지오코딩 입력  (NOT NULL)
        String ttl,             // ttl                                                 (NOT NULL)
        String cvlcptCn,        // cvlcpt_cn                                           (NOT NULL)
        String prgrsStts,       // prgrs_stts                                          (NOT NULL)
        String ansPrcsStts,     // ans_prcs_stts                                       (NOT NULL)
        String ansYmd,          // ans_ymd              점(.) 구분 → ISO 정규화
        String rwrdYn,          // rwrd_yn
        Integer prcsInstCnt,    // prcs_inst_cnt                                       (NOT NULL, 결측 0)
        String prcsInstNm,      // prcs_inst_nm                                        (NOT NULL)
        String prcsInstPicNm,   // prcs_inst_pic_nm     🔒 개인정보                     (NOT NULL)
        String dclrGrpNm,       // dclr_grp_nm
        String mbrGrpNm,        // mbr_grp_nm
        String dclrType,        // dclr_type            분류 CASE 의 입력               (NOT NULL)
        String prkCvlcptType,   // prk_cvlcpt_type      원천 분류명 — CASE 보다 우선한다
        String aprsRaw,         // aprs_cd              🔴 코드가 아니라 한글 원문(좋음/보통/나쁨 · 종전 긍정/부정/보통)
        // ── 여기부터는 원천이 채워 줄 수도 있는 "가공 컬럼" 이다(2026-09-01 현재 전량 NULL).
        //    최종값을 정하는 것은 Processor 이며, 여기서는 원천값을 그대로 전달만 한다.
        String cvlcptTypeCd,    // cvlcpt_type_cd       원천 분류코드 — 있으면 분류를 건너뛴다
        String emdCd,           // emd_cd               원천 행정동 — 있으면 공간매칭을 건너뛴다
        BigDecimal lat,         // lat                  원천 위도 — 있으면 지오코딩을 건너뛴다
        BigDecimal lot,         // lot                  원천 경도
        String srcCd,
        LocalDateTime loadDt) {         // src_cd               원천 유입경로코드 — 있으면 매핑을 건너뛴다

    /** load_dt가 없는 기존 호출의 호환 생성자. 운영 API에서는 load_dt를 읽는다. */
    public ComplaintOriginRow(String cvlcptId, String dclrYmd, LocalDateTime ocrnDt, String aplcntSe, String dclrFld, String srcPath, String dclrOcrnRgn, String ttl, String cvlcptCn, String prgrsStts, String ansPrcsStts, String ansYmd, String rwrdYn, Integer prcsInstCnt, String prcsInstNm, String prcsInstPicNm, String dclrGrpNm, String mbrGrpNm, String dclrType, String prkCvlcptType, String aprsRaw, String cvlcptTypeCd, String emdCd, BigDecimal lat, BigDecimal lot, String srcCd) {
        this(cvlcptId, dclrYmd, ocrnDt, aplcntSe, dclrFld, srcPath, dclrOcrnRgn, ttl, cvlcptCn, prgrsStts, ansPrcsStts, ansYmd, rwrdYn, prcsInstCnt, prcsInstNm, prcsInstPicNm, dclrGrpNm, mbrGrpNm, dclrType, prkCvlcptType, aprsRaw, cvlcptTypeCd, emdCd, lat, lot, srcCd, null);
    }

    /** 파싱 실패 진단용 — 🔒 민원 값은 싣지 않고 필드명·예외 종류만 남긴다(행마다 나올 수 있어 DEBUG). */
    private static final Logger log = LoggerFactory.getLogger(ComplaintOriginRow.class);

    /**
     * NOT NULL 인데 원천이 비어 있을 때 넣는 값. <b>빈 문자열을 쓰지 않는 것이 규칙</b>이다 —
     * 빈 문자열이면 "원천이 비어 있었다"는 사실이 데이터에서 사라진다.
     */
    public static final String PLACEHOLDER = "-";

    /**
     * {@code ocrn_dt} 파서 — {@code 2026-01-10 00:00:00.000}. 밀리초와 초는 선택 구간이라
     * {@code 2026-01-10 00:00} 도 받는다. 날짜/시각 구분자는 공백과 {@code T} 둘 다 허용한다.
     */
    private static final DateTimeFormatter OCRN_DT_FORMAT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter();

    /**
     * 조회 SQL 의 SELECT 목록 — <b>{@code SELECT *} 금지</b>(계획서 11-2).
     *
     * <p>순서는 이 record 의 필드 순서와 같다. {@code geom}·{@code risk_lvl_cd} 는
     * 여기 없다(위 클래스 주석 "버리는 컬럼"). 나열해서 얻는 것: 응답 크기 감소, 원천 스키마 변경에
     * 대한 내성, 그리고 <b>불필요한 개인정보 컬럼이 늘어나도 딸려오지 않는 것</b>.
     */
    public static final List<String> API_COLUMNS = List.of(
            "cvlcpt_id",
            "dclr_ymd",
            "ocrn_dt",
            "aplcnt_se",
            "dclr_fld",
            "src_path",
            "dclr_ocrn_rgn",
            "ttl",
            "cvlcpt_cn",
            "prgrs_stts",
            "ans_prcs_stts",
            "ans_ymd",
            "rwrd_yn",
            "prcs_inst_cnt",
            "prcs_inst_nm",
            "prcs_inst_pic_nm",
            "dclr_grp_nm",
            "mbr_grp_nm",
            "dclr_type",
            "prk_cvlcpt_type",
            "aprs_cd",
            "cvlcpt_type_cd",
            "emd_cd",
            "lat",
            "lot",
            "src_cd", "load_dt");

    /**
     * API 응답 원소 1개를 매핑한다. <b>real/mock 어댑터가 같은 이 메서드를 쓴다</b> — 매핑 규칙이
     * 두 벌이 되면 mock 테스트가 통과해도 real 이 다르게 동작한다.
     *
     * @throws DhubApiException {@code cvlcpt_id} 또는 {@code ocrn_dt} 가 비었을 때
     */
    public static ComplaintOriginRow fromApi(JsonNode node) {
        String cvlcptId = nullable(node, "cvlcpt_id");
        return new ComplaintOriginRow(
                cvlcptId,
                nullable(node, "dclr_ymd"),
                dateTime(node, "ocrn_dt"),
                nullable(node, "aplcnt_se"),
                nullable(node, "dclr_fld"),
                nullable(node, "src_path"),
                notNull(node, "dclr_ocrn_rgn"),
                notNull(node, "ttl"),
                notNull(node, "cvlcpt_cn"),
                notNull(node, "prgrs_stts"),
                notNull(node, "ans_prcs_stts"),
                isoDate(node, "ans_ymd"),
                nullable(node, "rwrd_yn"),
                intOrZero(node, "prcs_inst_cnt"),
                notNull(node, "prcs_inst_nm"),
                // 🔒 개인정보. 값이 비면 빈 문자열이 아니라 placeholder 를 넣는다(위 클래스 주석).
                notNull(node, "prcs_inst_pic_nm"),
                nullable(node, "dclr_grp_nm"),
                nullable(node, "mbr_grp_nm"),
                notNull(node, "dclr_type"),
                // 원천 분류명 — Processor 가 CASE 보다 먼저 본다(TSK-4109).
                nullable(node, "prk_cvlcpt_type"),
                // 감정은 원천 원문 그대로 담는다 — 코드 변환은 Processor 의 몫이다.
                nullable(node, "aprs_cd"),
                // ↓ 원천이 채워 줄 수도 있는 가공 컬럼. 비어 있으면 Processor 가 계산한다.
                nullable(node, "cvlcpt_type_cd"),
                nullable(node, "emd_cd"),
                decimal(node, "lat"),
                decimal(node, "lot"),
                nullable(node, "src_cd"),
                jeju.datahub.batch.collect.SourceTimestamp.parse(nullable(node, "load_dt")));
    }

    /** NOT NULL 컬럼 — 비면 {@value #PLACEHOLDER}. */
    private static String notNull(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? PLACEHOLDER : value;
    }

    /** NULL 허용 컬럼 — 비면 그대로 {@code null}(JSON {@code null} 을 문자열 "null" 로 만들지 않는다). */
    private static String nullable(JsonNode node, String field) {
        return text(node, field);
    }

    /**
     * 좌표 컬럼({@code lat}/{@code lot})을 {@link BigDecimal} 로 읽는다.
     *
     * <p>원천이 숫자로 주든 문자열로 주든 같은 값이 나와야 한다 — ClickHouse 는 {@code Decimal} 을
     * JSON 문자열로 흘려보내는 경우가 있다. <b>파싱 실패는 {@code null}</b> 이고, 그러면 Processor 가
     * 지오코딩 경로로 되돌아간다(값이 깨졌다고 행을 잃지 않는다).
     */
    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        String raw = value.asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            // 4.2 — 삼키는 사유를 남긴다(TSK-4336) — 값을 비우면 Processor 가 지오코딩 경로로 되돌아간다(행을 잃지 않는다).
            log.debug("숫자 파싱 실패 — NULL 로 둔다 field={} ({})", field, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 일자 문자열을 {@code yyyy-MM-dd} 로 정규화한다. 원천이 {@code 2025.5.7} 로 주는
     * {@code ans_ymd} 가 대상이며, 이미 ISO 면 그대로 통과한다.
     *
     * <p><b>파싱 실패는 {@code null}</b> — NULL 허용 컬럼이고, 수집을 통째로 실패시키는 것보다
     * 그 한 값을 비우는 편이 낫다. Processor 가 이 값을 {@code LocalDate.parse} 로 읽으므로
     * "이상한 문자열을 원본 그대로" 남기면 가공 단계에서 터진다.
     */
    static String isoDate(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip().replace('.', '-').replace('/', '-');
        if (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1); // '2025.5.7.' 꼴 방어
        }
        String[] parts = normalized.split("-");
        if (parts.length != 3) {
            return null;
        }
        try {
            return LocalDate.of(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            // 4.2 — 삼키는 사유를 남긴다(TSK-4336) — NULL 허용 컬럼이라 그 한 값만 비운다.
            log.debug("일자 파싱 실패 — NULL 로 둔다 field={} ({})", field, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * {@code ocrn_dt} → 발생일시. <b>파싱 실패는 {@code null}</b>(위 {@link #isoDate} 와 같은 이유).
     * Processor 가 신고일 자정으로 대체한다 — 서비스 테이블의 {@code ocrn_dt} 는 NOT NULL 이다.
     */
    static LocalDateTime dateTime(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.strip(), OCRN_DT_FORMAT);
        } catch (DateTimeParseException e) {
            // 4.2 — 삼키는 사유를 남긴다(TSK-4336) — Processor 가 신고일 자정으로 대체한다.
            log.debug("일시 파싱 실패 — NULL 로 둔다 field={} ({})", field, e.getClass().getSimpleName());
            return null;
        }
    }

    /** {@code prcs_inst_cnt} 는 integer NOT NULL — 결측은 0. */
    private static Integer intOrZero(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt(0);
    }

    /** 공백/JSON null 을 모두 {@code null} 로 정규화한다. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.isTextual() ? value.textValue() : value.asText();
        return raw == null || raw.isBlank() ? null : raw;
    }
}
