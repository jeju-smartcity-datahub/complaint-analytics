package jeju.datahub.batch.crackdown.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jeju.datahub.batch.cvlcpt.api.DhubApiException;

/**
 * 원천 ID는 nullable 메타데이터이며 내부 PK는 DB 시퀀스로 생성한다.
 * 중복 식별자는 원천 ID/load_dt를 제외한 정규화 7필드의 SHA-256이다.
 * 날짜는 마이크로초 고정, 숫자 과태료는 Double 표기로 정규화한다.
 * 내용이 달라진 원천 행은 별도 스냅샷이며 같은 내용은 기존 내부 PK를 유지한다.
 */
public record CrackdownOriginRow(
        LocalDateTime crdnDt,   // crdn_dt   (유일하게 타입 파싱 — dhub.crackdown-date-pattern)
        String ffnlg,           // ffnlg     (숫자면 Double.toString 정규화 "40000.0" — 해시 입력과 같은 값)
        String crdnMthd,        // crdn_mthd
        String carmdl,          // carmdl
        String orgnlCn,         // orgnl_cn
        String crdnPlc,         // crdn_plc
        String crdnAddr,        // crdn_addr
        String srcHash, String originPk, LocalDateTime loadDt) {

    public CrackdownOriginRow(LocalDateTime crdnDt, String ffnlg, String crdnMthd, String carmdl,
            String orgnlCn, String crdnPlc, String crdnAddr, String srcHash) {
        this(crdnDt, ffnlg, crdnMthd, carmdl, orgnlCn, crdnPlc, crdnAddr, srcHash, null, null);
    }

    /**
     * 원천 컬럼 목록 — 응답 파싱이 보는 필드 이름의 단일 출처. 목적지 테이블
     * ({@code parking.tb_crdn_dsctn})과 이름이 1:1 이므로 컬럼 추가는 DDL 변경과 함께 한다.
     */
    public static final List<String> SOURCE_COLUMNS = List.of(
            "crdn_dt", "ffnlg", "crdn_mthd", "carmdl", "orgnl_cn", "crdn_plc", "crdn_addr");

    /**
     * 응답 JSON 1행 → record 매핑.
     *
     * @param dateFormat  {@code dhub.crackdown-date-pattern} 으로 만든 포매터
     * @param datePattern 같은 패턴의 원문 — 파싱 실패 예외 메시지에 실어 원인을 즉시 드러낸다
     * @throws DhubApiException 행이 비었거나, 원천 7필드가 하나도 없거나,
     *                          crdn_dt 값이 있는데 파싱에 실패했을 때
     */
    public static CrackdownOriginRow fromApi(JsonNode node, DateTimeFormatter dateFormat, String datePattern) {
        if (node == null || node.isNull()) {
            throw new DhubApiException("crackdown 응답 행이 비어 있습니다.");
        }
        // 필드가 "없는" 것만 막는다 — 값이 null 인 필드(금액 결측 등)는 정상 결측이라 통과.
        if (SOURCE_COLUMNS.stream().allMatch(c -> node.path(c).isMissingNode())) {
            // 값은 싣지 않고 필드명만 — 로깅 규칙(값 비노출) 유지
            throw new DhubApiException("crackdown 응답 행에 원천 컬럼이 하나도 없습니다 — 원천 스키마 변경 의심. "
                    + "기대=" + SOURCE_COLUMNS + ", 수신 필드=" + fieldNames(node));
        }

        String rawDate = textOrNull(node, "crdn_dt");
        String ffnlg = normalizeFfnlg(node.path("ffnlg"));
        String crdnMthd = textOrNull(node, "crdn_mthd");
        String carmdl = textOrNull(node, "carmdl");
        String orgnlCn = textOrNull(node, "orgnl_cn");
        String crdnPlc = textOrNull(node, "crdn_plc");
        String crdnAddr = textOrNull(node, "crdn_addr");

        // 목적지가 nullable 이라 결측은 NULL 통과. 값이 있는데 못 읽는 것만 실패로 드러낸다.
        LocalDateTime crdnDt = null;
        if (rawDate != null && !rawDate.isBlank()) {
            try {
                crdnDt = LocalDateTime.parse(rawDate.trim(), dateFormat);
            } catch (DateTimeParseException e) {
                throw new DhubApiException(
                        "crdn_dt 파싱 실패(패턴 " + datePattern + "): '" + rawDate
                                + "'. 원천 포맷이 다르면 dhub.crackdown-date-pattern 을 조정하세요.", e);
            }
        }

        // 표기 차이(.000/.000000)가 같은 내용의 사본을 만들지 않게 날짜를 정규화한다.
        // 입력 순서(날짜, 금액, 방법, 차종, 원문, 장소, 주소)를 바꾸면 기존 적재 행과 해시가 어긋난다.
        String srcHash = jeju.datahub.batch.collect.SourceFingerprint.hash(
                jeju.datahub.batch.collect.SourceTimestamp.format(crdnDt), ffnlg, crdnMthd, carmdl,
                orgnlCn, crdnPlc, crdnAddr);
        return new CrackdownOriginRow(crdnDt, ffnlg, crdnMthd, carmdl, orgnlCn, crdnPlc, crdnAddr,
                srcHash, textOrNull(node, "crdn_id"),
                jeju.datahub.batch.collect.SourceTimestamp.parse(textOrNull(node, "load_dt")));
    }

    /** 수신 행의 필드명만 콤마로 이어붙인다 — 스키마 가드 예외 메시지용(값은 싣지 않는다). */
    private static String fieldNames(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        node.fieldNames().forEachRemaining(name -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        });
        return sb.toString();
    }

    /** 숫자 JSON의 40000과 40000.0을 같은 표기(40000.0)로 정규화한다. */
    static String normalizeFfnlg(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return Double.toString(v.asDouble());
        }
        return v.asText();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        // 숫자 컬럼(ffnlg)은 여기를 타지 않는다 — 경로별 JSON 숫자 표기 차이는 normalizeFfnlg 가 흡수한다.
        return v.asText();
    }
}
