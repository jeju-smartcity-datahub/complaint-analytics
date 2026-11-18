package jeju.datahub.analysis.service.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 추세 집계의 <b>평면 결과 행</b> {@code (pkltId, ym, 블록 4칸)} — {@code selectLotTrend} 전용.
 *
 * <p>🔁 <b>칸이 {@code cnt} 하나에서 넷으로 늘었다.</b> 증가율의 "이전" 이
 * <b>직전 동일 기간</b>(1차)이 됐고 그 기간에 민원이 없으면 <b>반 분할</b>(폴백)로
 * 내려앉기 때문에, 두 방식의 최근·이전을 <b>한 쿼리로</b> 받아 서비스가 고른다.
 *
 * <p>서비스({@code ZoneRcmdtnCalculator.applyTrend})가 {@code pkltId} 로 그룹핑해
 * ① 블록 건수 2칸({@code asgnCvlcptCntRcnt}·{@code asgnCvlcptCntBfr})을 합으로 채우고
 * ② <b>배정 0건인 달을 0 으로 채워</b> {@link jeju.datahub.analysis.service.ZoneRcmdtnTrendVO} 로
 * 재구성한다({@code TrendCountVO → TrendPointVO} 선례와 같은 구조다).
 *
 * <p>🔴 <b>매퍼는 배정이 있는 달만 돌려준다</b> — 이도이동 {@code 1960-3}·{@code 1963-7} 은 실제로
 * 2~3행뿐이다. 그대로 내리면 차트의 x축 칸 수가 주차장마다 달라진다.
 *
 * <p>🔴 <b>한 연월이 두 블록에 동시에 걸릴 수 있다</b> — 1차는 "이전" 이 민원 창 바로 앞이라
 * {@code bgng} 가 월 중이면 같은 달이 이전·최근 양쪽에 있다. 칸이 갈려 있어 섞이지 않는다.
 *
 * <p>응답에 직접 노출되지 않는 내부 타입이라 {@code service.impl} 에 둔다
 * ({@link ZoneRcmdtnQueryParam} 과 같은 자리).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnTrendCountVO {

    /** 주차장 ID — 그룹핑 키. 응답에서는 이 VO 가 주차장 아래로 들어가므로 다시 싣지 않는다 */
    private Long pkltId;

    /** 연월({@code YYYY-MM}). {@code to_char(ocrn_dt,'YYYY-MM')} 의 결과다 */
    private String ym;

    /** 🔁 그 달 중 <b>1차 최근 블록</b>(= 선택 기간 전체)에 든 배정 민원 건수 */
    private long cntRcnt;

    /** 🔁 그 달 중 <b>1차 이전 블록</b>(= 직전 같은 길이의 기간)에 든 배정 민원 건수 */
    private long cntBfr;

    /** 🔁 그 달 중 <b>폴백 최근 블록</b>(= 선택 기간의 마지막 절반)에 든 배정 민원 건수 */
    private long cntRcntSplit;

    /** 🔁 그 달 중 <b>폴백 이전 블록</b>(= 선택 기간의 처음 절반)에 든 배정 민원 건수 */
    private long cntBfrSplit;
}
