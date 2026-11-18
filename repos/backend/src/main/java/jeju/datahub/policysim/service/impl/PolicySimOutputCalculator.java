package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import jeju.datahub.policysim.service.PolicySimAplcnParam;
import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 정책 시뮬레이션 — 출력 지표 (명세 7장).
 *
 * <pre>
 * C1_e   = k_e × E1_e
 * ΔC_net = Σ (C1_e − C0_e)           ← Σ 는 NO_ENF(E0_e = 0) 를 뺀 전 읍면동
 *          (+ Σ_n (C1_n − C0_n)      ← 이면도로만 — 인접 지역 풍선효과)
 * R      = ΔC_net ÷ Σ C0_e           (음수 = 감소)
 * H0_T   = Σ(U0+E0) ÷ ΣS             ← Σ 는 S_e &gt; 0 인 읍면동만 (명세 10장 — 공급 0 은 혼잡 계산 제외)
 * H1_T   = Σ(U1+E1) ÷ ΣS1
 * I      = (H0_T − H1_T) ÷ H0_T      (양수 = 개선)
 * 비용    = N × 방식별 단가
 * 등급    = 1면당 비용(= 비용 ÷ 영향 면수 ΔS_eff, 명세 7.4) &lt; 저중경계 → LOW / &lt; 중고경계 → MID / 이상 → HIGH
 * </pre>
 *
 * <p>🔴 <b>반올림·퍼센트 변환은 이 클래스 한 곳</b>({@link #toVO})에서만 한다. R·I 는 <b>반올림 전</b> C·H 로 계산한다 —
 * 응답의 H({@code NUMERIC(6,3)})로 다시 나누면 연동 예시가 4.01 이 아니라 4.00 이 된다.
 * 퍼센트는 ×100 후 소수 둘째 자리 HALF_UP(비율 −0.27697 → −27.70).
 *
 * <p>🔴 <b>{@code calcSttsCd} 는 조회 범위 단위다</b> — 범위 ΣE0 = 0 이면 NO_ENF, 아니고 ΣS = 0 이면 NO_SPLY, 그 밖 null.
 * 읍면동별 NO_ENF·NO_SPLY 는 Σ 포함 여부만 정한다(결과 칸에 올리면 NO_ENF 가 두 시 모두에 있어 모든 시·전체 조회가 비워진다).
 * 범위 NO_ENF 면 R·I·C1·H1 은 null, C0(범위 실제 합)·H0(ΣS &gt; 0 이면)는 값. 차단은 화면 팝업이 한다.
 *
 * <p>추천(명세 7.5)은 서버가 판정하지 않는다 — 단건 응답에는 비교 대상이 없다(판정은).
 *
 * <p>산식은 DB 없이 검증할 수 있도록 정적 메서드로 둔다(구현과 같은 구조).
 */
@Component
public class PolicySimOutputCalculator {

    /** 공통코드 CST_LVL_CD */
    public static final String CST_LVL_LOW = "LOW";
    public static final String CST_LVL_MID = "MID";
    public static final String CST_LVL_HIGH = "HIGH";
    /** 미정 — 이면도로는 1m당 단가(c_line)가 없어 등급을 정할 수 없다(명세 7.4 · 공통코드) */
    public static final String CST_LVL_TBD = "TBD";

    /** 조회 범위 라벨 — 시군구도 읍면동도 없을 때. 보고서 이름짓기(HotspotReportNaming 등 ALL_REGION_NM)와 같은 문구 — 패키지 간 의존을 만들지 않으려고 같은 문자열을 둔다 */
    static final String RNG_ALL = "전체 지역";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 범위 Σ 와 R·I (반올림 전). 퍼센트 변환 전 비율이다.
     *
     * @param stts  조회 범위 단위 산출상태 — null = 정상
     * @param c0    C0 합 — R 의 분모(NO_ENF 제외). 범위가 NO_ENF 면 범위 실제 C0 합
     * @param c1    C1 합(NO_ENF 제외) — 범위 NO_ENF 면 null
     * @param e0    E0 합 (범위 전체)
     * @param s     S 합 (범위 전체)
     * @param h0    H0_T — ΣS = 0 이면 null
     * @param h1    H1_T — 범위 NO_ENF·NO_SPLY 면 null
     * @param r     R — 범위 NO_ENF 이거나 분모 ΣC0 = 0 이면 null
     * @param i     I — 범위 NO_ENF·NO_SPLY 면 null
     */
    public record Indicators(
            PolicySimCalcStts stts,
            double c0,
            Double c1,
            double e0,
            long s,
            Double h0,
            Double h1,
            Double r,
            Double i) {
    }

    /** 기준선 + 확충 결과 → 응답·저장 VO. 범위 라벨용으로 요청 지역을 받는다 */
    public PolicySimResultVO calculate(PolicySimBaseline baseline, ExpandResult expand, String sggNm, String emdCd) {
        return toVO(baseline, expand, sggNm, emdCd);
    }

    /** 기준선 + 이면도로 정비 결과 → 응답·저장 VO */
    public PolicySimResultVO calculate(PolicySimBaseline baseline, RepairResult repair, String sggNm, String emdCd) {
        return toVO(baseline, repair, sggNm, emdCd);
    }

    /**
     * 기준선 + 요금제 결과 → 응답·저장 VO.
     *
     * @param sens         {@link FeeSensitivity#compute} 결과(비율) — 범위 NO_ENF 면 null
     * @param feeAplcnZone 응답에 담을 급지 — emdCd 지정 시 서버가 채운 값
     */
    public PolicySimResultVO calculate(PolicySimBaseline baseline, FeeResult fee,
                                       Map<String, FeeSensitivity.Sensitivity> sens,
                                       String sggNm, String emdCd, String feeAplcnZone) {
        return toVO(baseline, fee, sens, sggNm, emdCd, feeAplcnZone);
    }

    /** 범위 산출상태 — ΣE0 = 0 → NO_ENF(우선), ΣS = 0 → NO_SPLY, 그 밖 null */
    public static PolicySimCalcStts rangeStts(List<ExpandEmdResult> emds) {
        double sumE0 = 0;
        long sumS = 0;
        for (ExpandEmdResult e : emds) {
            sumE0 += Math.max(0.0, e.base().e0());
            sumS += Math.max(0L, e.base().s());
        }
        if (sumE0 <= 0) {
            return PolicySimCalcStts.NO_ENF;
        }
        if (sumS <= 0) {
            return PolicySimCalcStts.NO_SPLY;
        }
        return null;
    }

    /** R·I 와 범위 Σ (반올림 전 · 비율) — 주차장 확충. 인접 지역 변화가 없다(명세 6.1) */
    public static Indicators indicators(ExpandResult expand) {
        return indicators(expand.emds(), 0.0);
    }

    /**
     * R·I 와 범위 Σ — 이면도로 정비 (명세 7.1).
     * {@code R} 분자에 인접 민원 증가분 {@code Σ_n k_n × 배분_n} 이 더해진다(순효과). 분모·혼잡 지수·포화·{@code calcStts} 는 대상만.
     */
    public static Indicators indicators(RepairResult repair) {
        return indicators(repair.emds(), repair.adjcDltCvlcpt());
    }

    /**
     * R·I 와 범위 Σ — 요금제 적용 (명세 7.1). 이면도로와 같이 {@code R} 분자에 인접 전이분의
     * 민원 증가 {@code Σ_n k_n × 배분_n} 이 더해진다(순효과). 분모·혼잡 지수·포화·{@code calcStts} 는 대상만.
     */
    public static Indicators indicators(FeeResult fee) {
        return indicators(fee.emds(), fee.adjcDltCvlcpt());
    }

    /**
     * R·I 와 범위 Σ (반올림 전 · 비율). 🔴 Σ 는 전부 <b>대상</b> 읍면동({@code emds})이다 —
     * 인접 지역은 {@code adjcDltCvlcpt}(Σ_n (C1_n − C0_n)) 하나로만 들어와 {@code R} 분자와 {@code C1} 합에 더해진다.
     * 그래서 {@code C1 = ΣC0 × (1 + R)} 이 인접 포함으로도 성립한다({@code aftrCvlcptCnt}).
     * 확충은 {@code adjcDltCvlcpt = 0} 이라 종전 값과 같다.
     *
     * @param emds          대상 읍면동별 변화 (C1·E1·U1·S1)
     * @param adjcDltCvlcpt 인접 민원 증가분 합 — 인접이 없으면 0
     */
    static Indicators indicators(List<ExpandEmdResult> emds, double adjcDltCvlcpt) {
        PolicySimCalcStts stts = rangeStts(emds);

        double c0All = 0;       // 범위 실제 C0 합 (NO_ENF 포함)
        double c0Enf = 0;       // R 분모 — NO_ENF 제외
        double c1Enf = 0;
        double e0 = 0;
        long s = 0;
        double h0Num = 0, h1Num = 0, s1Sum = 0;
        for (ExpandEmdResult e : emds) {
            PolicySimEmdBaseline b = e.base();
            c0All += b.c0();
            e0 += b.e0();
            s += b.s();
            // NO_ENF(E0 = 0) 는 k 가 없어 C1 도 없다 — R 의 Σ 에서 뺀다
            if (b.e0() > 0 && e.c1() != null) {
                c0Enf += b.c0();
                c1Enf += e.c1();
            }
            // 공급 0 은 혼잡 계산 제외
            if (b.s() > 0) {
                h0Num += b.u0() + b.e0();
                h1Num += e.u1() + e.e1();
                s1Sum += e.s1();
            }
        }

        boolean noEnf = stts == PolicySimCalcStts.NO_ENF;
        Double h0 = s > 0 ? h0Num / s : null;
        Double h1 = !noEnf && s > 0 ? h1Num / s1Sum : null;
        double c1Net = c1Enf + adjcDltCvlcpt;
        Double c1 = noEnf ? null : c1Net;
        // 분모 ΣC0 = 0(단속은 있으나 민원 0) 이면 비율이 정의되지 않는다 — 0/0 을 0 으로 메우지 않는다
        Double r = !noEnf && c0Enf > 0 ? (c1Net - c0Enf) / c0Enf : null;
        Double i = h0 != null && h1 != null && h0 > 0 ? (h0 - h1) / h0 : null;
        double c0 = noEnf ? c0All : c0Enf;
        return new Indicators(stts, c0, c1, e0, s, h0, h1, r, i);
    }

    /** 예상 비용(원) = N × 방식별 단가 (명세 7.4 — 공한지 N × c_vacant, 복층화·신규 N × 단가) */
    public static BigDecimal cstAmt(ExpandMethod method, int addPkltSlot, PolicySimParams p) {
        return p.get(method.cstVrblCd()).multiply(BigDecimal.valueOf(addPkltSlot));
    }

    /**
     * 비용 수준 — 1면당 비용 = 예상 비용 ÷ 영향 면수(공한지 ΔS_eff · 복층화·신규 N = ΔS_eff, 명세 7.4).
     * {@code < 저중경계} → LOW, {@code < 중고경계} → MID, 그 이상 → HIGH (경계값은 위 등급에 속한다).
     */
    public static String cstLvlCd(BigDecimal cstAmt, double affectedSlot, PolicySimParams p) {
        double perSlot = affectedSlot > 0 ? cstAmt.doubleValue() / affectedSlot : 0.0;
        BigDecimal per = BigDecimal.valueOf(perSlot);
        if (per.compareTo(p.get(PolicySimParams.CST_LOW_BNDRY)) < 0) {
            return CST_LVL_LOW;
        }
        if (per.compareTo(p.get(PolicySimParams.CST_HIGH_BNDRY)) < 0) {
            return CST_LVL_MID;
        }
        return CST_LVL_HIGH;
    }

    /** 응답·저장 VO 조립 — 반올림·퍼센트 변환은 여기서만 한다 */
    public static PolicySimResultVO toVO(PolicySimBaseline baseline, ExpandResult expand, String sggNm, String emdCd) {
        PolicySimParams p = baseline.params();
        Indicators ind = indicators(expand);
        BigDecimal cst = cstAmt(expand.method(), expand.addPkltSlot(), p);

        PolicySimResultVO vo = new PolicySimResultVO();
        vo.setAnlsRngCn(rangeLabel(expand.emds(), sggNm, emdCd));
        vo.setCrtrCvlcptCnt(scale(ind.c0(), 2));
        vo.setCrtrIlglCnt(scale(ind.e0(), 2));
        vo.setCrtrCngstIdx(scale(ind.h0(), 3));
        vo.setExsPkltCpcty(ind.s());
        vo.setVldAddPkltSlot(scale(expand.vldAddPkltSlot(), 2));
        vo.setCvlcptRdctRt(pct(ind.r()));
        vo.setCngstImpvRt(pct(ind.i()));
        vo.setAftrCngstIdx(scale(ind.h1(), 3));
        vo.setAftrCvlcptCnt(scale(ind.c1(), 2));
        vo.setCalcSttsCd(ind.stts() != null ? ind.stts().name() : null);
        vo.setExcsYn(expand.excs());
        vo.setCstAmt(plain(cst));
        vo.setCstLvlCd(cstLvlCd(cst, expand.vldAddPkltSlot(), p));
        vo.setAplcnParam(aplcnParam(baseline));
        return vo;
    }

    /**
     * 이면도로 정비 응답·저장 VO. 범위 NO_ENF·NO_SPLY 의 null 규칙은 확충과 같은 {@link #indicators(RepairResult)} 한 곳이다.
     * <ul>
     *   <li>{@code vldAddPkltSlot} = ΔS (확충의 ΔS_eff 칸 재사용)</li>
     *   <li>{@code aftrCvlcptCnt}·{@code cvlcptRdctRt} = 인접 증가분 포함 순효과 · 혼잡 지수는 대상만</li>
     *   <li>{@code adjcCvlcptCnt} = Σ(C1_n − C0_n) — 인접 없음(대상 가산)이면 0 · {@code adjcEmdCnt} = 배분에 쓴 인접 수</li>
     *   <li>{@code cstAmt} null · {@code cstLvlCd} {@code TBD}</li>
     * </ul>
     */
    public static PolicySimResultVO toVO(PolicySimBaseline baseline, RepairResult repair, String sggNm, String emdCd) {
        Indicators ind = indicators(repair);

        PolicySimResultVO vo = new PolicySimResultVO();
        vo.setAnlsRngCn(rangeLabel(repair.emds(), sggNm, emdCd));
        vo.setCrtrCvlcptCnt(scale(ind.c0(), 2));
        vo.setCrtrIlglCnt(scale(ind.e0(), 2));
        vo.setCrtrCngstIdx(scale(ind.h0(), 3));
        vo.setExsPkltCpcty(ind.s());
        vo.setVldAddPkltSlot(scale(repair.dltSlot(), 2));
        vo.setCvlcptRdctRt(pct(ind.r()));
        vo.setCngstImpvRt(pct(ind.i()));
        vo.setAftrCngstIdx(scale(ind.h1(), 3));
        vo.setAftrCvlcptCnt(scale(ind.c1(), 2));
        vo.setCalcSttsCd(ind.stts() != null ? ind.stts().name() : null);
        vo.setExcsYn(repair.excs());
        vo.setCstAmt(null);
        vo.setCstLvlCd(CST_LVL_TBD);
        vo.setRoadMtncType(repair.type().name());
        vo.setRoadMtncLen(repair.roadMtncLen());
        vo.setAdjcCvlcptCnt(scale(repair.adjcDltCvlcpt(), 2));
        vo.setAdjcEmdCnt(repair.adjcEmdCnt());
        vo.setAplcnParam(aplcnParam(baseline));
        return vo;
    }

    /**
     * 요금제 적용 응답·저장 VO. 범위 NO_ENF·NO_SPLY 의 null 규칙은 {@link #indicators(FeeResult)} 한 곳이다.
     * <ul>
     *   <li>{@code vldAddPkltSlot} null — 공급 불변(S1 = S)</li>
     *   <li>{@code aftrCvlcptCnt}·{@code cvlcptRdctRt} = 인접 전이분 포함 순효과(양수 = 민원 증가) · 혼잡 지수는 대상만</li>
     *   <li>{@code lvngVhclCnt}·{@code absrpCnt}·{@code trnsfCnt} = 이탈·흡수·전이(대) · 소수 둘째 자리 HALF_UP</li>
     *   <li>{@code cvlcptRdctRtRng} = 민감도 비율 → 퍼센트(× 100, 소수 둘째 자리 HALF_UP — {@link #pct} 와 같은 규칙)</li>
     *   <li>{@code adjcCvlcptCnt}·{@code adjcEmdCnt} = 4038 정의 재사용 · {@code cstAmt} null · {@code cstLvlCd} {@code TBD}</li>
     * </ul>
     */
    public static PolicySimResultVO toVO(PolicySimBaseline baseline, FeeResult fee,
                                         Map<String, FeeSensitivity.Sensitivity> sens,
                                         String sggNm, String emdCd, String feeAplcnZone) {
        Indicators ind = indicators(fee);

        PolicySimResultVO vo = new PolicySimResultVO();
        vo.setAnlsRngCn(rangeLabel(fee.emds(), sggNm, emdCd));
        vo.setCrtrCvlcptCnt(scale(ind.c0(), 2));
        vo.setCrtrIlglCnt(scale(ind.e0(), 2));
        vo.setCrtrCngstIdx(scale(ind.h0(), 3));
        vo.setExsPkltCpcty(ind.s());
        vo.setVldAddPkltSlot(null);
        vo.setCvlcptRdctRt(pct(ind.r()));
        vo.setCngstImpvRt(pct(ind.i()));
        vo.setAftrCngstIdx(scale(ind.h1(), 3));
        vo.setAftrCvlcptCnt(scale(ind.c1(), 2));
        vo.setCalcSttsCd(ind.stts() != null ? ind.stts().name() : null);
        vo.setExcsYn(fee.excs());
        vo.setCstAmt(null);
        vo.setCstLvlCd(CST_LVL_TBD);
        vo.setAdjcCvlcptCnt(scale(fee.adjcDltCvlcpt(), 2));
        vo.setAdjcEmdCnt(fee.adjcEmdCnt());
        vo.setFeeAplcnMthd(fee.mthd().name());
        vo.setFeeAplcnZone(feeAplcnZone);
        vo.setFeeIncrsRt(fee.feeIncrsRt() != null ? fee.feeIncrsRt().intValueExact() : null);
        vo.setAplcnTrgtSlot(fee.aplcnTrgtSlot());
        vo.setLvngVhclCnt(scale(fee.lvngVhclCnt(), 2));
        vo.setAbsrpCnt(scale(fee.absrpCnt(), 2));
        vo.setTrnsfCnt(scale(fee.trnsfCnt(), 2));
        vo.setCvlcptRdctRtRng(pctRange(sens));
        vo.setWrnngCn(FeePolicyCalculator.wrnngCn(fee));
        vo.setAplcnParam(aplcnParam(baseline));
        return vo;
    }

    /** 민감도 비율 → 퍼센트 [low, high]. 키 순서 유지 · 범위가 없는 계수는 값 null · 전체 null 은 null */
    static Map<String, List<BigDecimal>> pctRange(Map<String, FeeSensitivity.Sensitivity> sens) {
        if (sens == null) {
            return null;
        }
        Map<String, List<BigDecimal>> out = new LinkedHashMap<>();
        sens.forEach((k, v) -> {
            if (v == null) {
                out.put(k, null);
            } else {
                List<BigDecimal> pair = new ArrayList<>(2);
                pair.add(pct(v.low()));
                pair.add(pct(v.high()));
                out.put(k, List.copyOf(pair));
            }
        });
        return out;
    }

    /**
     * 조회 범위 라벨 — 읍면동 {@code "{시군구} {읍면동}"} · 시군구 {@code "{시군구} 전체"} · 그 밖 {@code "전체 지역"}.
     * 읍면동 조회면 시군구명은 기준선(TB_ADMDST) 값을 쓴다.
     */
    static String rangeLabel(List<ExpandEmdResult> emds, String sggNm, String emdCd) {
        if (emdCd != null && !emds.isEmpty()) {
            PolicySimEmdBaseline b = emds.get(0).base();
            return b.sggNm() + " " + b.emdNm();
        }
        if (sggNm != null) {
            return sggNm + " 전체";
        }
        return RNG_ALL;
    }

    /**
     * aplcn_param_json — 13계수 실제 적용값 + 두 기준 구간 (+ 이면도로 4계수 · 요금제 4계수 — 각 17종으로 해석한 경우만,
     * 그 밖은 null). 요금제 민감도의 상·하한은 담지 않는다(min_vl·max_vl 이 정본).
     */
    static PolicySimAplcnParam aplcnParam(PolicySimBaseline b) {
        PolicySimParams p = b.params();
        Map<String, BigDecimal> gSgg = new LinkedHashMap<>();
        gSgg.put(PolicySimParams.SGG_JEJU, plain(p.get(PolicySimParams.GEO_CVRG_CRCT_JEJU)));
        gSgg.put(PolicySimParams.SGG_SEOGWIPO, plain(p.get(PolicySimParams.GEO_CVRG_CRCT_SEOGWIPO)));
        return new PolicySimAplcnParam(
                plain(p.get(PolicySimParams.PCSHR_CRTR_VL)),
                plain(p.get(PolicySimParams.PCSHR_THRSH)),
                plain(p.get(PolicySimParams.ALPHA_VL)),
                plain(p.get(PolicySimParams.DSCL_MLTP)),
                plain(p.get(PolicySimParams.FIX_WGVL)),
                plain(p.get(PolicySimParams.VACLOT_GVBK_RT)),
                gSgg,
                plain(p.get(PolicySimParams.CST_VACLOT)),
                plain(p.get(PolicySimParams.CST_PBLPKLT)),
                plain(p.get(PolicySimParams.CST_MULTFLR)),
                plain(p.get(PolicySimParams.CST_LOW_BNDRY)),
                plain(p.get(PolicySimParams.CST_HIGH_BNDRY)),
                period(b.cvlcptBgngYmd(), b.cvlcptEndYmd()),
                period(b.crdnBgngYmd(), b.crdnEndYmd()),
                plain(p.get(RepairCoef.N100_RSDNC)),
                plain(p.get(RepairCoef.N100_CMRC)),
                plain(p.get(RepairCoef.ALPHA_B_VL)),
                plain(p.get(RepairCoef.BALLOON_RT)),
                plain(p.get(FeeCoef.PRC_ELST_VL)),
                plain(p.get(FeeCoef.PAID_DMND_RT)),
                plain(p.get(FeeCoef.TRNSF_RT)),
                plain(p.get(FeeCoef.TRNSF_LOCAL_RT)));
    }

    private static String period(LocalDate bgng, LocalDate end) {
        if (bgng == null || end == null) {
            return null;
        }
        return bgng.format(YMD) + "~" + end.format(YMD);
    }

    /** 비율 → 퍼센트 (×100, 소수 둘째 자리 HALF_UP). null 은 null */
    static BigDecimal pct(Double ratio) {
        if (ratio == null) {
            return null;
        }
        return BigDecimal.valueOf(ratio).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal scale(Double v, int scale) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * NUMERIC(12,4) 계수의 꼬리 0 제거 — {@code 0.8500 → 0.85}, {@code 38220000.0000 → 38220000}.
     * {@code stripTrailingZeros()} 만 쓰면 {@code 3.822E+7} 로 직렬화되므로 음수 스케일은 0 으로 되돌린다.
     */
    static BigDecimal plain(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0, RoundingMode.UNNECESSARY) : s;
    }
}
