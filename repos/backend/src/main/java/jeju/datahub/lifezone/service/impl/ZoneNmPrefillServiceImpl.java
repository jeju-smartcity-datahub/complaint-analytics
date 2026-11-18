package jeju.datahub.lifezone.service.impl;

import java.util.List;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jeju.datahub.analysis.service.impl.ZoneNameResolver;
import jeju.datahub.lifezone.service.ZoneNmPrefillResultVO;
import jeju.datahub.lifezone.service.ZoneNmPrefillService;
import lombok.RequiredArgsConstructor;

/**
 * 구역명 캐시 미리 채우기.
 *
 * <p>대상 조회 → 상한 = {@code limit} 인 세션 → 행마다 {@code session.resolve} → 집계.
 * 🔴 <b>캐시에 직접 쓰지 않는다</b> — 저장 여부(NOT_FOUND 저장 · 일시 실패 미저장)는
 * {@link ZoneNameResolver} 가 정한다. 그래서 집계도 반환 출처가 아니라 세션의 저장 수
 * ({@link ZoneNameResolver.Session#storedCount()})로 한다 — 확정 실패(NOT_FOUND)도 {@code EMD} 로
 * 돌아오고, POI 가 일시 실패한 역지오코딩 결과는 저장되지 않아 출처만으로는 갈 수 없다.
 *
 * <h2>🔴 {@code @Transactional} 을 걸지 않는다</h2>
 * 한 호출이 VWorld 를 최대 {@code limit} 번(최악 1건 약 8초) 부르므로, 트랜잭션을 걸면 그동안
 * DB 커넥션을 붙든 채 수십 초~수 분이 지난다. 읽기는 매퍼 호출마다 짧게 끝나고 저장은
 * {@code ZoneNameCacheWriter}({@code REQUIRES_NEW})가 건마다 커밋하므로 묶을 이유도 없다
 * (보고서 자동 생성 {@code ReportAutoCrtServiceImpl} 도 메서드에 {@code @Transactional} 을 두지 않는다).
 */
@Service
@RequiredArgsConstructor
public class ZoneNmPrefillServiceImpl extends EgovAbstractServiceImpl implements ZoneNmPrefillService {

    private static final Logger log = LoggerFactory.getLogger(ZoneNmPrefillServiceImpl.class);

    private final ZoneNmPrefillMapper prefillMapper;
    private final ZoneNameResolver zoneNameResolver;

    @Override
    public ZoneNmPrefillResultVO prefill(int gridSz, int limit) {
        int targetCnt = prefillMapper.countTargets(gridSz);
        List<ZoneNmPrefillTargetRow> rows = prefillMapper.selectTargets(gridSz, limit);

        ZoneNameResolver.Session session = zoneNameResolver.newSession(limit);
        for (ZoneNmPrefillTargetRow row : rows) {
            session.resolve(row.getCtrLat(), row.getCtrLot(), row.getEmdNm());
        }

        int resolvedCnt = session.storedCount();
        int transientCnt = rows.size() - resolvedCnt;
        int remainingCnt = prefillMapper.countRemaining(gridSz);
        log.info("[zone-nm-prefill] gridSz={} target={} picked={} resolved={} transient={} remaining={} tripped={}",
                gridSz, targetCnt, rows.size(), resolvedCnt, transientCnt, remainingCnt, session.isTripped());
        return new ZoneNmPrefillResultVO(gridSz, targetCnt, resolvedCnt, transientCnt, remainingCnt,
                session.isTripped());
    }
}
