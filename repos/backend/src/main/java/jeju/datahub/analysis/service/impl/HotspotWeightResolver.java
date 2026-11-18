package jeju.datahub.analysis.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import jeju.datahub.analysis.service.AnalysisWeightService;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import lombok.RequiredArgsConstructor;

/**
 * 집중구역 분석용 가중치 로더.
 *
 * <p>가중치 마스터({@code parking.TB_ANLS_VRBL_STNG})는 가중치 설정 기능이 소유한다 — 여기서는
 * <b>조회만</b> 한다. 저장·편집 경로를 이 클래스에 만들지 않는다.
 *
 * <p>🔴 <b>재정규화 산식을 여기 다시 구현하지 않는다.</b>
 * {@link AnalysisWeightService#selectEffectiveWeights(String)} 가 {@code use_yn='N'} 변수를 뺀 뒤
 * 남은 합계로 100 을 다시 맞춰(반올림 잔차는 {@code sort_seq} 최대 변수가 흡수) 돌려준다.
 * 산식을 두 곳에 두면 한쪽만 고쳐질 때 점수가 조용히 갈린다.
 *
 * <p>이 클래스가 하는 일은 그 결과를 <b>퍼센트(0~100) → 계수(0~1)</b> 로 바꿔 매퍼 파라미터
 * ({@code #{cvlcptWgt}} · {@code #{crdnWgt}})로 넘길 수 있게 하는 것뿐이다. 예) 민원 60 · 단속 40
 * → {@code 0.6} · {@code 0.4}, 단속을 끄면 민원 100 → {@code 1.0} · {@code 0}.
 *
 * <p>모든 계산은 {@link BigDecimal} 이다 — 컬럼이 {@code NUMERIC(5,2)} 이고, {@code double} 로
 * 합산하면 합계 100 판정이 간헐 실패한다.
 */
@Component
@RequiredArgsConstructor
public class HotspotWeightResolver {

    private static final Logger log = LoggerFactory.getLogger(HotspotWeightResolver.class);

    /** 이 로더가 읽는 분석유형 (ANLS_TYPE_CD 공통코드) */
    private static final String ANLS_TYPE_CD = "HOTSPOT";

    /** 민원빈도 변수코드 — 점수 산식의 {@code CVLCPT} 항 */
    private static final String VRBL_COMPLAINT = "COMPLAINT";
    /** 단속건수 변수코드 — 점수 산식의 {@code CRDN} 항 */
    private static final String VRBL_ENFORCE = "ENFORCE";

    /**
     * 변수구분코드 — 가중치({@code vrbl_se_cd = 'WGVL'}).
     *
     * <p>🔴 <b>점수 산식에 넣는 것은 가중치 행뿐이다</b>. 계수({@code CFC})는
     * 합계 개념이 없어, 섞이면 계수 값이 가중치 분모·분자로 들어가 종합점수가 조용히 틀어진다.
     * {@link AnalysisWeightService#selectEffectiveWeights(String)} 가 이미 같은 필터를 걸지만,
     * 계약이 바뀌어도 계수가 점수로 새지 않게 여기서 한 번 더 막는다.
     */
    private static final String VRBL_SE_WGVL = "WGVL";

    /**
     * 계수 나눗셈 스케일. 퍼센트(스케일 2)를 합계로 나눈 값이라 {@code 60 → 0.600000} 은 정확히
     * 떨어지고, 3분할처럼 나누어떨어지지 않는 경우에도 점수 순위를 가를 만한 오차가 남지 않는다.
     */
    private static final int COEF_SCALE = 6;

    private static final BigDecimal ONE = BigDecimal.ONE.setScale(COEF_SCALE);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(COEF_SCALE);

    /** 입력 가중치의 합계 기준 — 재정규화 없이 이 값과 정확히 같아야 한다. */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 입력 가중치의 소수 자리 상한 — 분석에 쓰는 재정규화 스케일({@code WGVL_SCALE})과 같은 2자리. */
    private static final int INPUT_MAX_SCALE = 2;

    /** 입력 가중치의 정수 자리 상한 — {@code 100} 이면 3자리. 산술 전에 지수 표기 입력을 끊는 데 쓴다. */
    private static final int INPUT_MAX_INTEGER_DIGITS = 3;

    /** 가중치 조정 여부 — 입력값이 설정값과 값으로 다르다. */
    private static final String AJMT_Y = "Y";
    /** 가중치 조정 여부 — 입력이 없거나 설정값과 같다. */
    private static final String AJMT_N = "N";

    /** 이 인터페이스는 모듈 간 계약이다 — 시그니처가 바뀌면 여기도 함께 고친다. */
    private final AnalysisWeightService analysisWeightService;

    /**
     * 사용 중인 가중치 변수({@code vrbl_se_cd='WGVL'})만 읽어 합계 기준 계수(0~1)로 환산한다.
     *
     * @return 민원·단속 계수와, 응답에 되비출 원본 가중치 목록
     * @throws ResponseStatusException 쓸 수 있는 변수가 하나도 없거나(전부 {@code use_yn='N'}),
     *         합계가 0 이거나, {@code COMPLAINT}·{@code ENFORCE} 가 둘 다 없을 때.
     *         <b>조용히 0점을 만들지 않는다</b> — 0점 결과는 "데이터가 없다"와 구분되지 않아
     *         설정 실수가 정상 응답으로 위장된다.
     */
    public HotspotWeights resolve() {
        // 1) vrbl_se_cd='WGVL' + use_yn='Y' 필터 + 재정규화까지 끝난 목록을 받는다 (퍼센트 0~100).
        List<AnalysisWeightVO> effective = analysisWeightService.selectEffectiveWeights(ANLS_TYPE_CD);
        if (effective == null || effective.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "분석에 사용할 가중치 변수가 없습니다 (anlsTypeCd=" + ANLS_TYPE_CD
                            + "). 변수가 모두 사용안함(use_yn='N') 이거나 가중치 합계가 0 입니다.");
        }

        // 2) 점수 산식이 아는 변수만 골라낸다.
        //    ⚠️ use_yn 은 CHAR(1) 의 'Y'/'N' 문자열이다(boolean 아님). 위 1) 이 이미 걸렀지만,
        //    계약이 바뀌어도 'N' 이 점수로 새지 않게 문자열 비교로 한 번 더 막는다.
        //    모르는 변수코드는 무시한다 — 설정에서 HOTSPOT 에 변수를 추가해도 이 분석이 깨지지 않게.
        BigDecimal complaintRt = null;
        BigDecimal enforceRt = null;
        Set<String> ignored = new LinkedHashSet<>();

        for (AnalysisWeightVO w : effective) {
            if (w.getStngVl() == null || !"Y".equals(w.getUseYn())) {
                continue;
            }
            // 🔴 가중치(WGVL) 행만 점수 산식 대상이다 — 계수(CFC)는 '무시한 변수' 로도 세지 않는다
            //    (산식에 항이 없어서가 아니라 성격이 다른 값이라 WARN 대상이 아니다).
            if (!VRBL_SE_WGVL.equals(w.getVrblSeCd())) {
                continue;
            }
            if (VRBL_COMPLAINT.equals(w.getVrblCd())) {
                complaintRt = w.getStngVl();
            } else if (VRBL_ENFORCE.equals(w.getVrblCd())) {
                enforceRt = w.getStngVl();
            } else {
                ignored.add(w.getVrblCd());
            }
        }

        // 3) 산식이 아는 변수가 하나도 없으면 실패시킨다.
        if (complaintRt == null && enforceRt == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "집중구역 점수 산식이 아는 변수가 없습니다 (anlsTypeCd=" + ANLS_TYPE_CD
                            + ", 필요=" + VRBL_COMPLAINT + "|" + VRBL_ENFORCE
                            + ", 사용중=" + usedCodes(effective) + ")");
        }
        if (!ignored.isEmpty()) {
            // 산식에 항이 없는 변수라 점수에 반영할 방법이 없다. 조용히 버리지 않고 드러낸다.
            log.warn("⚠️ [{}] 점수 산식이 모르는 변수를 무시했습니다: {}. 이 변수를 반영하려면 "
                    + "HotspotAnalysisMapper 의 점수 산식에 항을 추가해야 합니다.", ANLS_TYPE_CD, ignored);
        }

        // 4) 계수 = stng_vl ÷ 합계.
        //    합계는 '산식이 실제로 쓰는 변수'의 합이다 — 무시한 변수를 분모에 넣으면 계수 합이 1 미만이
        //    되어 종합점수가 설정 변경만으로 통째로 줄어든다("무시한다" = 분자에도 분모에도 안 넣는다).
        BigDecimal total = nz(complaintRt).add(nz(enforceRt));
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "가중치 합계가 0 이라 점수를 계산할 수 없습니다 (anlsTypeCd=" + ANLS_TYPE_CD + ")");
        }

        BigDecimal cvlcptWgt = complaintRt == null
                ? ZERO
                : complaintRt.divide(total, COEF_SCALE, RoundingMode.HALF_UP);
        // 반올림 잔차는 sort_seq 가 큰 ENFORCE 가 흡수해 계수 합을 정확히 1 로 맞춘다
        // (selectEffectiveWeights 의 잔차 처리와 같은 방식).
        BigDecimal crdnWgt = enforceRt == null ? ZERO : ONE.subtract(cvlcptWgt);

        return new HotspotWeights(cvlcptWgt, crdnWgt, List.copyOf(effective), AJMT_N);
    }

    /**
     * 사용자가 분석 화면에서 일시 조정한 가중치(%)를 검증해 계수(0~1)로 환산한다.
     *
     * <p>둘 다 {@code null} 이면 {@link #resolve()} 와 같다 — 새 필드를 보내지 않는 기존 호출부는 설정값을 쓴다.
     *
     * <p>🔴 <b>재정규화·합계 보정을 하지 않는다</b>. {@link #resolve()} 의 재정규화는 설정 저장값을 위한
     * 것이고, 입력값을 고쳐 분석하면 사용자가 고른 숫자와 다른 결과가 조용히 나온다 — 어긋나면 거절한다.
     *
     * <p>🔴 <b>설정 표({@code TB_ANLS_VRBL_STNG})에 쓰지 않는다.</b> 응답 {@code weights} 의 {@code stngVl} 만
     * 적용값으로 바꾼다. {@code setStngVl} 대상은 {@code selectEffectiveWeights} 가 호출마다 새로 만들어 주는
     * 복사본이라(캐시 없음) 다른 호출에 새지 않는다.
     *
     * @param cvlcptWgvl 민원 가중치(%)
     * @param crdnWgvl   단속 가중치(%)
     * @return 민원·단속 계수와, 민원·단속 2행(0% 행 포함)의 적용 가중치 목록
     * @throws ResponseStatusException 400 — 한쪽 누락 · 소수 셋째 자리 이상 · 합계가 100 이 아님 ·
     *         꺼진 지표가 있음 · 설정 범위({@code minVl}~{@code maxVl}) 밖
     */
    public HotspotWeights resolve(BigDecimal cvlcptWgvl, BigDecimal crdnWgvl) {
        if (cvlcptWgvl == null && crdnWgvl == null) {
            return resolve();
        }

        // ① 한쪽만 보내면 나머지를 추정하지 않는다.
        if (cvlcptWgvl == null || crdnWgvl == null) {
            throw badRequest("민원 가중치와 단속 가중치는 함께 입력해야 합니다.");
        }

        // ② 자릿수 검사 + 정규화. 🔴 이후 계산은 정규화한 값만 쓴다 — 1E+999999999 · 0E-999999999 같은
        //    값을 그대로 더하면 자릿수를 맞추느라 메모리·CPU 를 소진하거나 ArithmeticException 이 난다.
        BigDecimal cvlcpt = normalizeInput("민원", cvlcptWgvl);
        BigDecimal crdn = normalizeInput("단속", crdnWgvl);

        // ③ 합계 — 스케일이 달라도 같은 값이면 통과하도록 compareTo 로 비교한다.
        if (cvlcpt.add(crdn).compareTo(HUNDRED) != 0) {
            throw badRequest("민원 가중치와 단속 가중치의 합계는 100 이어야 합니다 (입력: "
                    + cvlcpt.toPlainString() + " + " + crdn.toPlainString() + ").");
        }

        // ④ 꺼진 지표는 입력으로 되살리지 않는다.
        AnalysisWeightVO complaint = null;
        AnalysisWeightVO enforce = null;
        List<AnalysisWeightVO> effective = analysisWeightService.selectEffectiveWeights(ANLS_TYPE_CD);
        if (effective != null) {
            for (AnalysisWeightVO w : effective) {
                if (!VRBL_SE_WGVL.equals(w.getVrblSeCd()) || !"Y".equals(w.getUseYn())) {
                    continue;
                }
                if (VRBL_COMPLAINT.equals(w.getVrblCd())) {
                    complaint = w;
                } else if (VRBL_ENFORCE.equals(w.getVrblCd())) {
                    enforce = w;
                }
            }
        }
        if (complaint == null || enforce == null) {
            throw badRequest("민원·단속 지표를 모두 쓸 수 있을 때만 가중치를 조정할 수 있습니다. "
                    + "설정에서 지표 사용 여부와 가중치를 확인해 주세요.");
        }

        // ⑤ 설정 범위. min_vl·max_vl 은 NOT NULL 컬럼이다(마이그레이션 67).
        requireInRange("민원", cvlcpt, complaint);
        requireInRange("단속", crdn, enforce);

        // 🔴 조정 여부는 setStngVl 이 설정값을 덮어쓰기 전에 판정한다 — 뒤에서 비교하면 항상 'N' 이다.
        //    compareTo 인 이유: 설정값은 스케일이 달라(60.00) equals 면 같은 값도 'Y' 가 된다.
        String wgvlAjmtYn = cvlcpt.compareTo(complaint.getStngVl()) != 0 || crdn.compareTo(enforce.getStngVl()) != 0
                ? AJMT_Y
                : AJMT_N;

        // ⑥ 계수 = 값 ÷ 100. 잔차는 resolve() 와 같이 ENFORCE 가 흡수해 계수 합을 정확히 1 로 맞춘다.
        complaint.setStngVl(cvlcpt);
        enforce.setStngVl(crdn);

        BigDecimal cvlcptWgt = cvlcpt.divide(HUNDRED, COEF_SCALE, RoundingMode.HALF_UP);
        BigDecimal crdnWgt = ONE.subtract(cvlcptWgt);

        return new HotspotWeights(cvlcptWgt, crdnWgt, List.of(complaint, enforce), wgvlAjmtYn);
    }

    /**
     * 입력값의 소수·정수 자리를 검사하고, 끝자리 0 과 지수 표기를 걷어낸 값을 돌려준다.
     * 정수 자리는 지수 표기를 원본 그대로 재기 위해 {@code long} 으로 계산한다.
     *
     * <p>🔴 정수 자리 검사가 {@code stripTrailingZeros} 보다 먼저다 — 100E+2147483647 은 끝자리 0 을
     * 걷어내면 scale 이 int 범위를 벗어나 {@code ArithmeticException} 이 난다.
     */
    private static BigDecimal normalizeInput(String label, BigDecimal value) {
        if ((long) value.precision() - value.scale() > INPUT_MAX_INTEGER_DIGITS) {
            throw badRequest(label + " 가중치가 허용 범위를 벗어났습니다.");
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > INPUT_MAX_SCALE) {
            throw badRequest(label + " 가중치는 소수 둘째 자리까지 입력할 수 있습니다.");
        }
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static void requireInRange(String label, BigDecimal value, AnalysisWeightVO row) {
        if (value.compareTo(row.getMinVl()) < 0 || value.compareTo(row.getMaxVl()) > 0) {
            throw badRequest(label + " 가중치는 " + row.getMinVl().stripTrailingZeros().toPlainString()
                    + "~" + row.getMaxVl().stripTrailingZeros().toPlainString()
                    + " 범위여야 합니다 (입력: " + value.toPlainString() + ").");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 오류 메시지용 — 실제로 넘어온 변수코드 나열 (원인 파악이 메시지만으로 되게 한다). */
    private static String usedCodes(List<AnalysisWeightVO> weights) {
        List<String> codes = new ArrayList<>(weights.size());
        for (AnalysisWeightVO w : weights) {
            codes.add(String.valueOf(w.getVrblCd()));
        }
        return String.join(", ", codes);
    }

    /**
     * 집중구역 점수 산식에 넣을 가중치 계수 한 벌.
     *
     * <p>{@code cvlcptWgt + crdnWgt = 1} 이다(둘 중 하나만 있으면 그쪽이 1). 점수는
     * {@code 민원 ÷ 최대 민원 × cvlcptWgt + 단속 ÷ 최대 단속 × crdnWgt} 을 조회 결과 최댓값 대비
     * 100 으로 환산한 값이라 <b>0~100</b> 이다.
     * 이 클래스의 역할은 그대로다 — 퍼센트를 계수로 바꿔 넘긴다.
     *
     * <p>{@code weights} 는 {@link AnalysisWeightService#selectEffectiveWeights(String)} 가 돌려준
     * <b>퍼센트(0~100) 원본</b>이다 — 계수가 아니다. 응답
     * ({@code HotspotAnalysisResultVO.weights})에 그대로 되비춰 화면 부제 "민원 60% / 단속 40%" 의
     * 출처가 된다.
     *
     * <p>{@code wgvlAjmtYn} 은 사용자 입력이 설정값과 값으로 달랐는지다({@code 'Y'}/{@code 'N'}).
     *
     * <p>⚠️ 매퍼에는 이 레코드를 파라미터 객체로 통째로 넘기지 말고 {@code @Param("cvlcptWgt")} 처럼
     * 값을 하나씩 넘긴다 — 레코드 접근자는 {@code get} 접두가 없어 MyBatis 가 프로퍼티로 읽지 못한다.
     */
    public record HotspotWeights(
            BigDecimal cvlcptWgt,
            BigDecimal crdnWgt,
            List<AnalysisWeightVO> weights,
            String wgvlAjmtYn) {
    }
}
