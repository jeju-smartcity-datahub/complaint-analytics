package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 검증을 마친 분석 계수 일시 입력 1회분 (TSK-4325). {@code AnlsVrblOvrdResolver} 만 만든다.
 *
 * <p>🔴 설정 표에 쓰지 않는다 — 그 요청의 계산과 보고서 적용 내역에만 쓰인다(D8).
 */
public final class AnlsVrblOvrd {

    private static final String AJMT_Y = "Y";
    private static final String AJMT_N = "N";
    private static final String EXPSR_Y = "Y";
    private static final String EXPSR_N = "N";

    /** 입력 전용 파서 — 같은 키가 두 번 오거나 객체 뒤에 다른 값이 붙으면 조용히 넘기지 않고 형식 오류로 본다. */
    private static final ObjectMapper PARSER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AnlsVrblScope scope;
    /** 그 화면에서 쓰는(허용 목록 ∩ {@code use_yn='Y'}) 설정 행. 적용 내역의 원천이다. */
    private final List<AnalysisWeightVO> rows;
    /** 끝자리 0 을 걷은 입력값. */
    private final Map<String, BigDecimal> values;

    private AnlsVrblOvrd(AnlsVrblScope scope, List<AnalysisWeightVO> rows, Map<String, BigDecimal> values) {
        this.scope = scope;
        this.rows = List.copyOf(rows);
        this.values = Map.copyOf(values);
    }

    /** 🔴 검증하지 않는다 — {@code AnlsVrblOvrdResolver} 전용이다. */
    public static AnlsVrblOvrd of(AnlsVrblScope scope, List<AnalysisWeightVO> rows, Map<String, BigDecimal> values) {
        return new AnlsVrblOvrd(scope, rows, values);
    }

    /**
     * GET 쿼리의 JSON 문자열을 Map 으로 바꾼다(D1). 값 검증은 {@code AnlsVrblOvrdResolver} 가 한다.
     *
     * @return 비었으면 빈 Map(생략 = 설정값)
     * @throws ResponseStatusException 400 — JSON 객체가 아니거나 값을 숫자로 읽을 수 없을 때
     */
    public static Map<String, BigDecimal> parse(String vrblVls) {
        if (vrblVls == null || vrblVls.isBlank()) {
            return Map.of();
        }
        Map<String, BigDecimal> parsed;
        try {
            parsed = PARSER.readValue(vrblVls, new TypeReference<LinkedHashMap<String, BigDecimal>>() { });
        } catch (JsonProcessingException | NumberFormatException | ArithmeticException e) {
            // 🔴 입력 원문·파서 메시지를 응답에 싣지 않는다.
            throw invalidFormat();
        }
        if (parsed == null) {
            throw invalidFormat();
        }
        return parsed;
    }

    /** 입력이 하나도 없으면 {@code true}. 설정값과 같은 값만 온 경우는 {@link #ajmtYn()} 으로 본다. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * {@code stngVl} 을 입력값으로 덮은 복사본을 돌려준다. 🔴 입력 행은 바꾸지 않는다.
     *
     * <p>🔴 이 스코프의 분석유형 행만 덮는다 — {@code POLICY_SIM} 에도 같은 코드({@code PCSHR_CRTR_VL} 등)가 있다.
     */
    public List<AnalysisWeightVO> apply(List<AnalysisWeightVO> source) {
        List<AnalysisWeightVO> copies = new ArrayList<>(source.size());
        for (AnalysisWeightVO row : source) {
            AnalysisWeightVO copy = new AnalysisWeightVO();
            // 세터를 나열하지 않는다 — VO 에 필드가 늘어도 복사본에서 빠지지 않는다.
            BeanUtils.copyProperties(row, copy);
            BigDecimal value = values.get(row.getVrblCd());
            if (value != null && scope.getAnlsTypeCd().equals(row.getAnlsTypeCd())) {
                copy.setStngVl(value);
            }
            copies.add(copy);
        }
        return copies;
    }

    /** 입력값이 있으면 그 값, 없으면 {@code fallback} 결과. 폴백 계약은 호출부 그대로다. */
    public BigDecimal value(String vrblCd, Supplier<BigDecimal> fallback) {
        BigDecimal value = values.get(vrblCd);
        return value != null ? value : fallback.get();
    }

    /**
     * 그 화면이 쓰는 설정 행 1건의 복사본 — 엑셀 재계산 데이터({@code wbk.vrbls})의 이름·범위 출처다(TSK-4340 D4).
     *
     * <p>🔴 {@code AnlsVrblOvrdResolver} 가 이미 읽은 {@code selectWeightsByType} 결과다 — 범위를 위해 설정 표를 다시 읽지 않는다.
     *
     * @return 허용 목록 밖이거나 행이 없거나 {@code use_yn='N'} 이면 {@code null}
     */
    public AnalysisWeightVO row(String vrblCd) {
        for (AnalysisWeightVO row : rows) {
            if (row.getVrblCd() != null && row.getVrblCd().equals(vrblCd)) {
                AnalysisWeightVO copy = new AnalysisWeightVO();
                BeanUtils.copyProperties(row, copy);
                return copy;
            }
        }
        return null;
    }

    /** 입력값이 설정값과 <b>값으로</b> 다른 행이 있으면 {@code 'Y'}(D11 — {@code 60.00} 과 {@code 60} 은 같다). */
    public String ajmtYn() {
        for (AnalysisWeightVO row : rows) {
            BigDecimal value = values.get(row.getVrblCd());
            if (value != null && (row.getStngVl() == null || value.compareTo(row.getStngVl()) != 0)) {
                return AJMT_Y;
            }
        }
        return AJMT_N;
    }

    /**
     * 적용 내역(D10) — {@code {ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, bscStngVl, stngVl, aplcnVl, expsrYn}]}}.
     *
     * <p>🆕 TSK-4346 — {@code bscStngVl}(기본값)·{@code expsrYn}(노출여부 — null 은 {@code 'Y'})을 더했다(키 추가만 · 하위 호환).
     * 🔴 생성 시점 값으로 저장한다 — 보고서는 이 스냅샷으로 그리며 열 때 현재 설정을 다시 읽지 않는다.
     * {@code ajmtYn} 판정은 그대로다(기본값이 아니라 설정값과 비교한다).
     *
     * <p>🔴 조정 안 한 행도 담는다. 숫자는 끝자리 0 을 걷는다 — DB 값({@code 60.0000})을 그대로 싣으면
     * POST 응답과 저장 후 되읽은 스냅샷의 표기가 갈린다.
     */
    public Map<String, Object> toAjmtJson() {
        List<Map<String, Object>> vrbls = new ArrayList<>(rows.size());
        for (AnalysisWeightVO row : rows) {
            Map<String, Object> vrbl = new LinkedHashMap<>();
            vrbl.put("vrblCd", row.getVrblCd());
            vrbl.put("vrblNm", row.getVrblNm());
            vrbl.put("unitNm", row.getUnitNm());
            vrbl.put("bscStngVl", plain(row.getBscStngVl()));   // TSK-4346 — 기본값(생성 시점)
            vrbl.put("stngVl", plain(row.getStngVl()));
            vrbl.put("aplcnVl", plain(value(row.getVrblCd(), row::getStngVl)));
            vrbl.put("expsrYn", EXPSR_N.equals(row.getExpsrYn()) ? EXPSR_N : EXPSR_Y);   // TSK-4346 — null = 노출
            vrbls.add(vrbl);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ajmtYn", ajmtYn());
        json.put("vrbls", vrbls);
        return json;
    }

    /** {@code stripTrailingZeros} 만 쓰면 {@code 100} 이 {@code 1E+2} 가 된다. */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static ResponseStatusException invalidFormat() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "vrblVls 는 변수코드와 숫자로 이루어진 JSON 객체여야 합니다.");
    }
}
