package jeju.datahub.policysim.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 기준선 집계 구간 (반열림 {@code [bgng, endExcl)}). (TSK-4037 Step 2)
 *
 * <p>🔴 <b>구간은 민원·단속에 따로 잡는다</b> — 같은 절대 날짜를 양쪽에 걸면 민원이 재적재로 이동한 만큼 잘린다.
 */
@Getter
@ToString
@AllArgsConstructor
public class PolicySimBaselineQueryParam {

    /** 단속 {@code crdn_dt} 시작 (포함) */
    private final LocalDateTime crdnBgngDt;

    /** 단속 {@code crdn_dt} 끝 (제외) */
    private final LocalDateTime crdnEndDtExcl;

    /** 민원 {@code ocrn_dt} 시작 (포함) */
    private final LocalDateTime cvlcptBgngDt;

    /** 민원 {@code ocrn_dt} 끝 (제외) */
    private final LocalDateTime cvlcptEndDtExcl;

    /**
     * 읍면동 코드 필터 (TSK-4038 Step 3 — 인접 읍면동 기준선). {@code null} 이면 {@code tb_admdst} 전 행(4037 동작).
     * 🔴 빈 목록은 넘기지 않는다 — {@code IN ()} 문법 오류다. 호출부({@link PolicySimBaselineCalculator})가 쿼리 전에 끊는다.
     */
    private final List<String> emdCds;

    /** 전 읍면동 (TSK-4037 경로) */
    public PolicySimBaselineQueryParam(LocalDateTime crdnBgngDt, LocalDateTime crdnEndDtExcl,
                                       LocalDateTime cvlcptBgngDt, LocalDateTime cvlcptEndDtExcl) {
        this(crdnBgngDt, crdnEndDtExcl, cvlcptBgngDt, cvlcptEndDtExcl, null);
    }
}
