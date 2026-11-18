package jeju.datahub.policysim.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 산출 시점의 적용 계수·기준 구간 ({@code aplcn_param_json} · 응답 {@code aplcnParam}).
 *
 * <p>🔴 계수는 설정에서 바뀌고 민원 구간은 재적재마다 이동하므로 함께 남기지 않으면
 * 과거 결과를 다시 열 때 숫자가 달라진다. 담는 것: POLICY_SIM 13계수(이면도로·요금제는 각 +4 — ·4039)의 <b>실제 적용값</b>(폴백 포함,
 * {@code g_sgg} 는 두 시 값 모두) + 민원·단속 기준 구간. 요청 조건(정책·방식·면수·기간·지역)은
 * {@code TB_ANLS_EXCN.anls_param_json} 이 진다. {@code lag}(차트 발현 시차)는 서버 계산에 쓰지 않아 담지 않는다.
 *
 * <p>record 로 둔 이유: {@code gSgg}·{@code cVacant} 처럼 소문자 한 글자 뒤 대문자인 이름은 Lombok getter
 * ({@code getGSgg})를 거치면 Jackson 이 {@code gsgg} 로 내린다 — record 는 컴포넌트 이름 그대로 나간다.
 *
 * @param occ          기준점유율 {@code PCSHR_CRTR_VL}
 * @param occTh        포화점유율임계값 {@code PCSHR_THRSH}
 * @param alpha        흡수율 {@code ALPHA_VL}
 * @param m            적발배수 {@code DSCL_MLTP}
 * @param wFixed       고정형단속가중치 {@code FIX_WGVL}
 * @param rRet         공한지연반환율 {@code VACLOT_GVBK_RT} (/년)
 * @param gSgg         좌표결측보정 {@code GEO_CVRG_CRCT_*} — 키 {@code 제주시}·{@code 서귀포시}
 * @param cVacant      공한지조성비 {@code CST_VACLOT} (원/면)
 * @param cPublic      신규조성비 {@code CST_PBLPKLT} (원/면)
 * @param cMultflr     복층화조성비 {@code CST_MULTFLR} (원/면)
 * @param cstLowBndry  비용저중경계 {@code CST_LOW_BNDRY} (원/면)
 * @param cstHighBndry 비용중고경계 {@code CST_HIGH_BNDRY} (원/면)
 * @param cvlcptPrd    민원 기준 구간 {@code YYYYMMDD~YYYYMMDD} (양끝 포함) — 민원 0건이면 null
 * @param crdnPrd      단속 기준 구간 {@code YYYYMMDD~YYYYMMDD} (양끝 포함)
 * @param n100Rsdnc    주거지역형 100m당 확보구획 {@code N100_RSDNC} — 이면도로(REPAIR)만, 확충은 null
 * @param n100Cmrc     상업지역형 100m당 확보구획 {@code N100_CMRC} — 이면도로만
 * @param alphaB       구획 흡수율 {@code ALPHA_B_VL} — 이면도로만
 * @param balloonRt    풍선효과율 {@code BALLOON_RT} — 이면도로만
 * @param eps          가격탄력성 ε {@code PRC_ELST_VL} — 요금제(FEE)만, 그 밖은 null
 * @param lambda       유료전환 수요감소율 λ {@code PAID_DMND_RT} — 요금제만
 * @param tau          전이율 τ {@code TRNSF_RT} — 요금제만
 * @param tauLocal     전이 중 같은 지역 비율 τ_local {@code TRNSF_LOCAL_RT} — 요금제만.
 *                     🔴 민감도 상·하한은 담지 않는다({@code min_vl}·{@code max_vl} 이 정본)
 */
@Schema(description = "산출 시점 적용 계수·기준 구간")
public record PolicySimAplcnParam(
        BigDecimal occ,
        BigDecimal occTh,
        BigDecimal alpha,
        BigDecimal m,
        BigDecimal wFixed,
        BigDecimal rRet,
        Map<String, BigDecimal> gSgg,
        BigDecimal cVacant,
        BigDecimal cPublic,
        BigDecimal cMultflr,
        BigDecimal cstLowBndry,
        BigDecimal cstHighBndry,
        String cvlcptPrd,
        String crdnPrd,
        BigDecimal n100Rsdnc,
        BigDecimal n100Cmrc,
        BigDecimal alphaB,
        BigDecimal balloonRt,
        BigDecimal eps,
        BigDecimal lambda,
        BigDecimal tau,
        BigDecimal tauLocal) {
}
