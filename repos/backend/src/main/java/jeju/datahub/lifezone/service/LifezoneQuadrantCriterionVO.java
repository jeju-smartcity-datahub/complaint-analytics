package jeju.datahub.lifezone.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 사분면 범례 1행.
 *
 * <p>🔴 <b>범례 문구에 임계값 실제 숫자를 넣는다.</b> 임계값은 격자크기·기간·지역이 바뀔 때마다
 * 다시 산출되므로 프론트에 박을 상수가 없다 — 서버가 만든 {@link #lgndTxt} 를 그대로 출력한다.
 */
@Schema(description = "사분면 범례 1행")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneQuadrantCriterionVO {

    /** 사분면코드 */
    @Schema(description = "사분면코드", example = "Q1")
    private String qdrn;

    /** 사분면명 */
    @Schema(description = "사분면명", example = "부족 의심")
    private String qdrnNm;

    /** 판정 근거 요약 */
    @Schema(description = "판정 근거 요약", example = "민원 많음 · 공영주차면 적음")
    private String qdrnExpln;

    /**
     * 범례 문구 — 임계값 숫자가 박혀 있다.
     *
     * <p>표시용 숫자는 <b>올림</b>이다({@code 23.4건 → 24건 이상}). 🔴 그러나 <b>판정은 원값으로
     * 한다</b> — 반올림한 값으로 다시 판정하면 경계 격자의 사분면이 바뀐다.
     */
    @Schema(description = "범례 문구", example = "부족 의심 (민원 24건 이상 · 공영주차면 6면 이하)")
    private String lgndTxt;

    /** 해당 사분면 격자 수 */
    @Schema(description = "해당 사분면 격자 수", example = "106")
    private int gridCnt;

    /** 해당 사분면 민원 합계 */
    @Schema(description = "해당 사분면 민원 합계", example = "8831")
    private int cvlcptCnt;

    /** 해당 사분면 중 공영주차면 0 인 격자 수 */
    @Schema(description = "공영주차면 0 인 격자 수", example = "97")
    private int zeroSplyGridCnt;

    /**
     * 사분면 4종 범례를 만든다. 집계가 0건인 사분면도 <b>행을 남긴다</b> — 범례에서 항목이
     * 사라지면 색과 의미의 대응이 조회마다 달라진다.
     *
     * @param cntByQdrn 사분면별 집계 (없는 사분면은 0 으로 채운다)
     * @param cvlcptHighThld 민원高 임계값 (원값)
     * @param splyLowThld    공급低 임계값 (원값)
     */
    public static List<LifezoneQuadrantCriterionVO> allOf(
            Map<LifezoneQuadrant, int[]> cntByQdrn,
            double cvlcptHighThld, double splyLowThld) {
        List<LifezoneQuadrantCriterionVO> rows = new ArrayList<>(LifezoneQuadrant.values().length);
        for (LifezoneQuadrant q : LifezoneQuadrant.values()) {
            int[] agg = cntByQdrn.getOrDefault(q, new int[] {0, 0, 0});
            LifezoneQuadrantCriterionVO vo = new LifezoneQuadrantCriterionVO();
            vo.setQdrn(q.name());
            vo.setQdrnNm(q.getQdrnNm());
            vo.setQdrnExpln(q.getQdrnExpln());
            vo.setLgndTxt(lgndTxtOf(q, cvlcptHighThld, splyLowThld));
            vo.setGridCnt(agg[0]);
            vo.setCvlcptCnt(agg[1]);
            vo.setZeroSplyGridCnt(agg[2]);
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 범례 문구를 만든다. 표시 숫자는 올림({@code Math.ceil}) — 판정은 원값이다.
     *
     * <p>민원 축은 {@code >=} 라 올림이 안전 방향이고(24건 이상 = 23.4 이상을 포함),
     * 공급 축은 {@code <=} 라 <b>내림</b>해야 같은 방향이다(6.8면 이하를 "6면 이하" 로 적으면
     * 표시가 좁아지지만, 올리면 실제로는 빠지는 격자를 포함하는 것처럼 읽힌다).
     */
    private static String lgndTxtOf(LifezoneQuadrant q, double cvlcptHighThld, double splyLowThld) {
        long hi = (long) Math.ceil(cvlcptHighThld);
        long lo = (long) Math.floor(splyLowThld);
        String cvlcpt = q == LifezoneQuadrant.Q1 || q == LifezoneQuadrant.Q2
                ? "민원 " + hi + "건 이상"
                : "민원 " + hi + "건 미만";
        String sply = q == LifezoneQuadrant.Q1 || q == LifezoneQuadrant.Q3
                ? "공영주차면 " + lo + "면 이하"
                : "공영주차면 " + lo + "면 초과";
        return q.getQdrnNm() + " (" + cvlcpt + " · " + sply + ")";
    }
}
