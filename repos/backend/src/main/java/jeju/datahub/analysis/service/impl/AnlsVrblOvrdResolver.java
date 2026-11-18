package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.AnlsVrblScope.Codes;
import lombok.RequiredArgsConstructor;

/**
 * 분석 계수 일시 입력을 설정 행과 대조해 검증한다 (TSK-4325 D3·D4). 규칙은 이 클래스 한 곳에 둔다.
 *
 * <p>🔴 분석·생활권 경로를 덮는 {@code @RestControllerAdvice} 가 없어 {@link ResponseStatusException}(400)을 직접 던진다.
 * 🔴 설정 표에 쓰지 않는다(D8). 조회 권한은 로그인만 요구한다(D9).
 */
@Component
@RequiredArgsConstructor
public class AnlsVrblOvrdResolver {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String VRBL_SE_WGVL = "WGVL";
    private static final String USE_Y = "Y";
    /** 노출여부 'N' — 숨김 행(TSK-4346). */
    private static final String EXPSR_N = "N";

    /**
     * 입력 자릿수 상한 — 컬럼이 {@code NUMERIC(12,4)} 다. 설정에 담을 수 없는 값을 받지 않고,
     * 🔴 {@code 1E-999999999} 를 그대로 더해 자릿수를 맞추느라 자원을 소진하는 것을 막는다(TSK-4306 선례).
     */
    private static final int INPUT_MAX_INTEGER_DIGITS = 8;
    private static final int INPUT_MAX_SCALE = 4;

    private final AnalysisWeightMapper analysisWeightMapper;

    /**
     * @param vrblVls 입력값. {@code null}·빈 Map 이면 빈 덮어쓰기다(설정값 그대로) — 적용 내역은 그때도 전체 행을 담는다(D10)
     * @throws ResponseStatusException 400 — 목록 밖 코드 · 행 없음 · 숨김({@code expsrYn='N'}) · {@code useYn='N'} · null 값 · 범위 밖 ·
     *         자릿수 초과 · 소수인 정수 전용 · 경계 역전 · 가중치 합계 100 아님
     */
    public AnlsVrblOvrd resolve(AnlsVrblScope scope, Map<String, BigDecimal> vrblVls) {
        Map<String, AnalysisWeightVO> rows = new LinkedHashMap<>();
        for (AnalysisWeightVO row : analysisWeightMapper.selectWeightsByType(scope.getAnlsTypeCd())) {
            if (scope.getVrblCds().contains(row.getVrblCd())) {
                rows.put(row.getVrblCd(), row);
            }
        }

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (vrblVls != null) {
            for (Map.Entry<String, BigDecimal> input : vrblVls.entrySet()) {
                values.put(input.getKey(), validateValue(scope, rows, input.getKey(), input.getValue()));
            }
        }
        for (List<String> chain : scope.getBndryChains()) {
            requireIncreasing(chain, rows, values);
        }
        requireWeightSums(rows, values);

        List<AnalysisWeightVO> usedRows = rows.values().stream()
                .filter(row -> USE_Y.equals(row.getUseYn()))
                .toList();
        return AnlsVrblOvrd.of(scope, usedRows, values);
    }

    /** 행 단위 규칙을 보고, 끝자리 0 을 걷은 값을 돌려준다. */
    private static BigDecimal validateValue(AnlsVrblScope scope, Map<String, AnalysisWeightVO> rows,
                                            String vrblCd, BigDecimal value) {
        if (!scope.getVrblCds().contains(vrblCd)) {
            throw badRequest("이 화면에서 조정할 수 없는 변수입니다: " + vrblCd);
        }
        AnalysisWeightVO row = rows.get(vrblCd);
        if (row == null) {
            throw badRequest("설정에 없는 변수입니다: " + vrblCd);
        }
        // 🆕 TSK-4346 — 숨김(expsr_yn='N') 고정 계수는 화면에 없으므로 일시 조정 대상이 아니다.
        //    🔴 검사는 입력 키(여기) 한 곳뿐 — rows·usedRows 에서는 빼지 않는다(적용 내역·엑셀 계수 셀이 숨김 행을 전제한다).
        //    🔴 판정은 "N".equals — null(단위 테스트 픽스처)은 노출이다.
        if (EXPSR_N.equals(row.getExpsrYn())) {
            throw badRequest("이 화면에서 조정할 수 없는 변수입니다: " + vrblCd);
        }
        if (!USE_Y.equals(row.getUseYn())) {
            throw badRequest("사용하지 않는 변수는 조정할 수 없습니다: " + vrblCd);
        }
        if (value == null) {
            throw badRequest("값이 없습니다: " + vrblCd);
        }

        // 🔴 정수 자리 검사가 strip 보다 먼저다 — 100E+2147483647 은 strip 하면 scale 이 int 범위를 벗어나 예외가 난다.
        if ((long) value.precision() - value.scale() > INPUT_MAX_INTEGER_DIGITS) {
            throw badRequest("허용 범위를 벗어났습니다: " + vrblCd);
        }
        BigDecimal stripped = value.stripTrailingZeros();
        // 🔴 scale 이 아니라 값으로 본다 — 20.0000·20.0 은 정수다.
        if (stripped.scale() > 0 && isIntegerOnly(vrblCd)) {
            throw badRequest("정수만 입력할 수 있습니다: " + vrblCd);
        }
        if (stripped.scale() > INPUT_MAX_SCALE) {
            throw badRequest("소수 넷째 자리까지 입력할 수 있습니다: " + vrblCd);
        }
        BigDecimal normalized = stripped.scale() < 0 ? stripped.setScale(0) : stripped;

        BigDecimal min = row.getMinVl();
        BigDecimal max = row.getMaxVl();
        if ((min != null && normalized.compareTo(min) < 0) || (max != null && normalized.compareTo(max) > 0)) {
            throw badRequest("허용 범위를 벗어났습니다: " + vrblCd + " = " + normalized.toPlainString()
                    + " (minVl " + fmt(min) + " ~ maxVl " + fmt(max) + ")");
        }
        return normalized;
    }

    private static boolean isIntegerOnly(String vrblCd) {
        return Codes.SPLY_INT_VRBLS.contains(vrblCd) || Codes.HOTSPOT_GRD_BNDRYS.contains(vrblCd);
    }

    /** 🔴 입력을 설정값 위에 얹어 판정한다. 묶음에 입력이 하나도 없으면 설정값만의 문제라 보지 않는다. */
    private static void requireIncreasing(List<String> chain, Map<String, AnalysisWeightVO> rows,
                                          Map<String, BigDecimal> values) {
        if (chain.stream().noneMatch(values::containsKey)) {
            return;
        }
        String lowerCd = null;
        BigDecimal lower = null;
        for (String vrblCd : chain) {
            BigDecimal current = overlaid(vrblCd, rows, values);
            if (current == null) {
                continue;
            }
            if (lower != null && current.compareTo(lower) <= 0) {
                throw badRequest("경계는 " + lowerCd + " < " + vrblCd + " 여야 합니다: "
                        + fmt(lower) + ", " + fmt(current));
            }
            lowerCd = vrblCd;
            lower = current;
        }
    }

    /**
     * 가중치 합계 100 — 저장 검사와 같이 {@code WGVL}·{@code use_yn='Y'} 행을 {@code anls_trgt_cd} 묶음마다 본다.
     * 재정규화하지 않는다. 🔴 {@code rows} 가 이미 허용 목록으로 걸러져 집중구역 가중치는 대상이 아니다(D2).
     */
    private static void requireWeightSums(Map<String, AnalysisWeightVO> rows, Map<String, BigDecimal> values) {
        Map<String, List<AnalysisWeightVO>> groups = new LinkedHashMap<>();
        for (AnalysisWeightVO row : rows.values()) {
            if (VRBL_SE_WGVL.equals(row.getVrblSeCd()) && USE_Y.equals(row.getUseYn())) {
                // groupingBy 는 null 키에서 NPE 라 쓰지 않는다 — anls_trgt_cd NULL 도 한 묶음이다.
                groups.computeIfAbsent(row.getAnlsTrgtCd(), k -> new ArrayList<>()).add(row);
            }
        }
        for (List<AnalysisWeightVO> group : groups.values()) {
            if (group.stream().noneMatch(row -> values.containsKey(row.getVrblCd()))) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (AnalysisWeightVO row : group) {
                BigDecimal current = overlaid(row.getVrblCd(), rows, values);
                if (current != null) {
                    sum = sum.add(current);
                }
            }
            if (sum.compareTo(HUNDRED) != 0) {
                throw badRequest("가중치 합계가 100 이 아닙니다 (현재 " + fmt(sum) + ")");
            }
        }
    }

    private static BigDecimal overlaid(String vrblCd, Map<String, AnalysisWeightVO> rows,
                                       Map<String, BigDecimal> values) {
        BigDecimal value = values.get(vrblCd);
        if (value != null) {
            return value;
        }
        AnalysisWeightVO row = rows.get(vrblCd);
        return row == null ? null : row.getStngVl();
    }

    private static String fmt(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
