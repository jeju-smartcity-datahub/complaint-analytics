package jeju.datahub.lifezone.service.impl;

import org.springframework.dao.DataAccessException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.lifezone.service.LifezoneTarget;
import jeju.datahub.lifezone.service.LifezoneTargetVO;

/**
 * 분석유형 목록에 <b>유형별 원천 보유 구간</b>을 채워 내린다. (TSK-4089)
 *
 * <h2>🔴 왜 필요한가 — "실행했더니 전부 0" 을 막는 장치다</h2>
 * 화면의 기간 세그먼트(최근 1/3/6개월)는 <b>오늘</b> 기준으로 펴진다. 그런데 유형마다 기간 기준
 * 컬럼이나 집계 조건이 다르면 보유 구간도 갈린다.
 *
 * <table>
 *   <caption>2026-09-13 로컬 실측</caption>
 *   <tr><th>유형</th><th>기준 컬럼</th><th>집계 조건</th><th>보유 구간</th></tr>
 *   <tr><td>민원건수</td><td>{@code ocrn_dt}</td><td>미분류({@code '99'}) 제외 · 좌표 무관</td>
 *       <td>2025-11-04 ~ 2026-08-31</td></tr>
 *   <tr><td>수급</td><td>{@code ocrn_dt}</td><td>전 유형 · 좌표 유효 범위만</td>
 *       <td>2025-11-04 ~ 2026-08-31</td></tr>
 * </table>
 *
 * 🔁 <b>2026-09-13 — 수급이 {@code dclr_ymd} 에서 {@code ocrn_dt} 로 옮겨오면서 두 구간이
 * 같아졌다.</b> 그전에는 수급이 {@code 2025-01-01~2025-08-31} 이라 화면 기본값(최근 1개월,
 * <b>오늘</b> 기준)으로 <b>영원히 0건</b>이었다.
 *
 * <p>🔴 <b>원인이 없어졌어도 이 장치는 남긴다</b> — 위 표처럼 집계 조건이 달라 구간이 완전히
 * 같다는 보장이 없고, 원천이 또 바뀔 수 있다. 보유 구간을 실행 <b>전에</b> 내려 주지 않으면
 * 화면은 기본값으로 0건을 내고 사용자는 그것을 고장으로 읽는다.
 *
 * <h2>왜 별도 컴포넌트인가</h2>
 * 구간의 출처가 유형마다 다른 매퍼다({@code LifezoneAnalysisMapper} ↔ {@code LifezoneSupplyMapper}).
 * 어느 한 서비스에 넣으면 그 서비스가 다른 유형의 매퍼를 알게 되어 <b>도메인 안에서 역참조</b>가
 * 생긴다 — 유형이 늘 때마다 그 참조도 늘어난다. 목록을 만드는 일만 여기 모은다.
 *
 * <p>⚠️ 구간 조회는 {@code min}/{@code max} 두 번이고 <b>설정 패널이 마운트 시 1회</b> 부르는
 * 경로에서만 돈다(분석 응답의 {@code availableTargets} 는 정적 목록을 그대로 쓴다).
 */
@Component
public class LifezoneTargetCatalog {

    private static final Logger log = LoggerFactory.getLogger(LifezoneTargetCatalog.class);

    private final LifezoneAnalysisMapper lifezoneAnalysisMapper;
    private final LifezoneSupplyMapper lifezoneSupplyMapper;
    private final LifezoneCngstMapper lifezoneCngstMapper;

    public LifezoneTargetCatalog(LifezoneAnalysisMapper lifezoneAnalysisMapper,
                                 LifezoneSupplyMapper lifezoneSupplyMapper,
                                 LifezoneCngstMapper lifezoneCngstMapper) {
        this.lifezoneAnalysisMapper = lifezoneAnalysisMapper;
        this.lifezoneSupplyMapper = lifezoneSupplyMapper;
        this.lifezoneCngstMapper = lifezoneCngstMapper;
    }

    /**
     * 분석유형 3종 + 각 유형의 원천 보유 구간.
     *
     * <p>🔴 <b>구간 조회 실패가 목록을 죽이지 않는다.</b> 보유 구간은 "있으면 더 친절한" 부가
     * 정보이고, 목록 자체는 세그먼트를 그리는 데 반드시 필요하다 — 여기서 예외가 나가면 화면이
     * 분석유형을 하나도 못 고르는 상태가 된다. 실패하면 구간만 {@code null} 로 두고 WARN 을 남긴다.
     */
    public List<LifezoneTargetVO> selectTargets() {
        List<LifezoneTargetVO> targets = LifezoneTargetVO.allOf();
        DataRangeVO ocrnRange = rangeOf("민원건수(ocrn_dt · 99 제외)", lifezoneAnalysisMapper::selectDataRange);
        DataRangeVO splyRange = rangeOf("수급(ocrn_dt · 좌표유효)", lifezoneSupplyMapper::selectDataRange);
        DataRangeVO cngstRange = rangeOf("혼잡도(단속 crdn_dt · 좌표유효)", lifezoneCngstMapper::selectDataRange);

        for (LifezoneTargetVO target : targets) {
            DataRangeVO range = rangeFor(LifezoneTarget.valueOf(target.getAnlsTrgtCd()),
                    ocrnRange, splyRange, cngstRange);
            if (range != null) {
                target.setDataRange(range.getDataBgngYmd(), range.getDataEndYmd());
            }
        }
        return targets;
    }

    /**
     * 유형 → 구간 출처.
     *
     * <p>🔴 <b>유형마다 자기 원천의 구간을 쓴다.</b> 다른 유형의 구간을 빌려 채우면 화면이
     * "이 기간엔 데이터가 있다" 고 잘못 안내한다. 혼잡도(TSK-4090)는 지표의 주 입력인
     * <b>단속 {@code crdn_dt}</b>(좌표 유효) 구간이다 — 공급(공영주차장)은 시점이 없다.
     */
    private static DataRangeVO rangeFor(LifezoneTarget target, DataRangeVO ocrnRange,
                                        DataRangeVO splyRange, DataRangeVO cngstRange) {
        return switch (target) {
            case CVLCPT_CNT -> ocrnRange;
            case SPLY_DMND -> splyRange;
            case CNGST -> cngstRange;
        };
    }

    private static DataRangeVO rangeOf(String label, java.util.function.Supplier<DataRangeVO> query) {
        try {
            return query.get();
        } catch (DataAccessException | IllegalStateException e) { // 4.3: DB 쿼리 실패 · 상태 오류(ISE)
            log.warn("분석유형 보유 구간 조회 실패 — 구간 없이 목록만 내립니다. 대상={} 사유={}", label, e.getClass().getSimpleName());
            log.debug("분석유형 보유 구간 조회 실패 상세", e);   // 4.1 — 예외 원문(toString)은 DEBUG 로만 (TSK-4336)
            return null;
        }
    }
}
