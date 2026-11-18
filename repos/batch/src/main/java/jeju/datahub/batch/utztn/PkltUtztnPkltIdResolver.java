package jeju.datahub.batch.utztn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.pblpklt.PkltNameAddrNormalizer;
import jeju.datahub.batch.utztn.api.PkltUtztnOriginRow;

/**
 * 원천(이름·주소) → 우리 {@code parking.tb_pblpklt.pklt_id} 해석 — <b>2단 전략</b> (TSK-4189 Step 7).
 *
 * <h2>🔴 원천에 주차장 키가 없다</h2>
 * {@code pklt_id}·{@code origin_pk} 둘 다 없어 <b>문자열로</b> 찾아야 한다. 우리 {@code pklt_id} 는 DB 채번
 * IDENTITY 라(마이그레이션 70) 원천 값과 아무 관계가 없다 — 그래서 원천 숫자를 그대로 쓰면 <b>실재하는
 * 다른 주차장</b>에 붙어 FK 위반도 없이 조용히 틀린다.
 *
 * <pre>
 * 1단 주소 — ① 읍면 접두만 제거   '남원읍 남원리 180' → '남원리 180'
 *            ② 정규화 주소가 같은 후보를 모아 ③ MIN(pklt_id) — "가장 먼저 등록된 주차장에 붙인다"
 * 2단 이름 — 1단이 못 찾은 것만. 괄호 내용 제거 + 공백 전부 제거
 *            '천지공영주차빌딩(공작물)' → '천지공영주차빌딩'
 *            🔴 후보가 유일할 때만 붙인다. 2개 이상이면 스킵(NM_AMBIGUOUS)
 * </pre>
 * 실측(2026-09-29 · 원천 369건 · 부모 표 2,263행): 1단 <b>364</b> · 2단 <b>5</b> · 미해석 <b>0</b>.
 *
 * <h2>🔴 순서를 바꾸지 않는다</h2>
 * 이름을 1단으로 올리면 해석이 364 → 93 으로 떨어지고, 같은 도로명을 쓰는 노상 구역이 엉킨다
 * (`남광북1길` → 후보 3개). 주소가 1차, 이름은 주소로 못 찾은 것만 줍는 2차다.
 *
 * <h2>🔴 검산 수단이 없다 — 그래서 기록으로 대체한다</h2>
 * 원천이 주는 필드는 이름·주소·면수뿐이고 셋 다 검산에 못 쓴다 — 면수는 <b>정책으로 변하고</b>
 * (걸면 면수가 바뀐 주차장의 데이터가 조용히 누락된다), 이름은 2단 매칭 수단이며, 주소는 1단 키다.
 * 그래서 매칭 결과 전량을 {@code batch.tb_pklt_utztn_mtch} 에 남긴다({@link PkltUtztnMatchAuditRepository}).
 *
 * <h2>🔴 맵 3개의 수명 — {@code beforeStep()} 에서 새로 만든다</h2>
 * 싱글턴 빈이라 필드를 재사용하면 Job 실행 간 살아남는다. 부모 표를 <b>전량 재적재해 {@code pklt_id} 가
 * 재채번된 뒤</b> 2회차에서 멀쩡한 주차장이 전량 {@code DUP_TARGET} 으로 스킵될 수 있고, 그 사고는
 * 행이 <b>줄어드는</b> 것이라 {@code HAVING count(*) > 1} 로는 잡히지 않는다.
 *
 * <p>🔴 그러려면 Step 빌더에 {@code .listener(pkltIdResolver)} 로 <b>명시 등록</b>돼야 한다 —
 * Processor 가 필드로 들고 있다고 자동 등록되지 않는다(등록을 빠뜨리면 맵이 빈 채 돌아 전량 미해석이고
 * 컴파일은 통과한다). 등록 위치는 체인의 <b>맨 마지막</b>이다 — {@code StepExecutionListener} 오버로드는
 * {@code SimpleStepBuilder} 를 돌려주므로 그 뒤에 {@code .skip()} 을 이을 수 없다.
 */
@Component
public class PkltUtztnPkltIdResolver implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PkltUtztnPkltIdResolver.class);

    private final JdbcTemplate jdbcTemplate;
    private final jeju.datahub.batch.utztn.api.DhubPkltUtztnApiClient apiClient;

    /** 정규화 주소 → 오름차순 pklt_id 목록 (1단 후보). */
    private Map<String, List<Long>> byAddr = Map.of();
    /** 정규화 이름 → 오름차순 pklt_id 목록 (2단 후보). */
    private Map<String, List<Long>> byName = Map.of();
    /** 원천 면수 대조용 — pklt_id → 우리 pklt_cpcty. 경고 기록에만 쓴다(매칭을 막지 않는다). */
    private Map<Long, Integer> cpcty = Map.of();
    /**
     * 🔴 <b>착수 시 일괄 배정한 결과</b> — 원천 주차장 정체성 → 판정. {@code resolve()} 는 조회만 한다.
     *
     * <p>스트리밍 중에 배정하면 <b>순서에 좌우된다</b> — 같은 정규화 주소에 원천 2건이 걸리면 먼저 흘러온
     * 쪽이 {@code MIN(pklt_id)} 를 차지해 이름이 맞는 쪽을 밀어낸다(2026-09-30 E2E 가 실제로 그랬다).
     */
    private Map<SrcKey, Match> decisions = Map.of();

    public PkltUtztnPkltIdResolver(JdbcTemplate jdbcTemplate,
                                  jeju.datahub.batch.utztn.api.DhubPkltUtztnApiClient apiClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.apiClient = apiClient;
    }

    /** 원천 주차장 정체성 — {@code DUP_TARGET} 판정 키다. */
    record SrcKey(String nm, String addr) {
        static SrcKey of(PkltUtztnOriginRow row) {
            return new SrcKey(row.pkltNm(), row.pkltAddr());
        }
    }

    /** 해석 결과 — 붙었으면 {@code pkltId} 가 있고, 못 붙었으면 {@code failRsn} 이 있다. */
    public record Match(Long pkltId, String mtchStp, int candCnt, boolean ambiguous,
                        boolean cpctyDiffers, String failRsn, String normAddr, String normNm) {
        public boolean resolved() {
            return pkltId != null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 정규화 — 🔴 둘 다 원천과 우리 표 **양쪽**에 같이 적용한다.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 1단: 읍면 접두만 제거한다. 🔴 <b>다필지 주소를 첫 지번으로 자르지 않는다</b> —
     * 그 정규화는 해석을 367까지만 올리면서 우리 표 안 1:N 을 29 → 43 으로 늘린다
     * (2단 이름 폴백이 같은 5건을 1:N 위험 없이 잡으므로 불필요하다).
     *
     * <p>본문은 공용 {@link PkltNameAddrNormalizer#normAddr} 로 옮겼다(TSK-4254) — 안전점검 잡과 같은 규칙.
     */
    static String normAddr(String addr) {
        return PkltNameAddrNormalizer.normAddr(addr);
    }

    /**
     * 2단: 괄호 내용 제거 + 공백 전부 제거.
     * 🔴 {@code \(.*\)} 는 greedy 라 {@code 'A(가)B(나)' → 'A'} 가 된다(가운데 본문까지 삭제) —
     * {@code [^)]*} 로 좁힌다.
     *
     * <p>본문은 공용 {@link PkltNameAddrNormalizer#normName} 로 옮겼다(TSK-4254) — 안전점검 잡과 같은 규칙.
     */
    static String normName(String nm) {
        return PkltNameAddrNormalizer.normName(nm);
    }

    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void beforeStep(StepExecution stepExecution) {
        Map<String, List<Long>> addrMap = new HashMap<>();
        Map<String, List<Long>> nameMap = new HashMap<>();
        Map<Long, Integer> cpctyMap = new HashMap<>();

        // 🔴 소멸 행(extsh_dt IS NOT NULL)도 후보에 넣는다 — 빼면 주차장이 소멸 처리되는 순간
        //    과거 달의 이용 데이터까지 갑자기 미해석으로 바뀐다. FK 는 소멸 행도 허용한다.
        jdbcTemplate.query(
                "SELECT pklt_id, pklt_nm, pklt_addr, pklt_cpcty FROM parking.tb_pblpklt",
                rs -> {
                    long id = rs.getLong("pklt_id");
                    addrMap.computeIfAbsent(normAddr(rs.getString("pklt_addr")), k -> new ArrayList<>()).add(id);
                    nameMap.computeIfAbsent(normName(rs.getString("pklt_nm")), k -> new ArrayList<>()).add(id);
                    int c = rs.getInt("pklt_cpcty");
                    if (!rs.wasNull()) {
                        cpctyMap.put(id, c);
                    }
                });
        addrMap.values().forEach(java.util.Collections::sort);
        nameMap.values().forEach(java.util.Collections::sort);

        this.byAddr = addrMap;
        this.byName = nameMap;
        this.cpcty = cpctyMap;
        log.info("주차장 후보 맵 적재 — 정규화 주소 {}키 · 정규화 이름 {}키", addrMap.size(), nameMap.size());

        this.decisions = assignAll(apiClient.fetchLots());
    }

    /**
     * 원천 주차장 전량을 <b>순서 독립적으로</b> 배정한다 (TSK-4189 Step 7).
     *
     * <pre>
     * 1차 주소·유일        후보가 하나면 그것
     * 2차 주소·이름 일치   후보 여럿 + 그중 정규화 이름이 같은 것이 유일하면 그것   ← 🔴 순서 의존을 없애는 지점
     * 3차 주소·MIN         후보 여럿 + 이름으로 못 가르면 아직 안 쓰인 후보 중 MIN(pklt_id)
     * 4차 이름 폴백        주소로 아예 못 찾은 것 — 정규화 이름 후보가 유일할 때만
     * </pre>
     * 남으면 {@code NM_AMBIGUOUS}(2단 후보 여럿) · {@code DUP_TARGET}(쓸 수 있는 후보가 다 소진) ·
     * {@code ADDR_NM_BOTH_MISS}(아무 것도 못 찾음)다.
     */
    private Map<SrcKey, Match> assignAll(List<String[]> lots) {
        Map<SrcKey, Match> out = new HashMap<>();
        Map<SrcKey, String[]> norm = new HashMap<>();      // key → {normAddr, normNm}
        Map<Long, SrcKey> taken = new HashMap<>();

        for (String[] lot : lots) {
            SrcKey key = new SrcKey(lot[0], lot[1]);
            norm.put(key, new String[] { normAddr(lot[1]), normName(lot[0]) });
        }
        // 🔴 정렬해 순회한다 — HashMap 순서에 결과가 좌우되면 같은 원천으로 두 번 돌릴 때 배정이 달라진다.
        List<SrcKey> sorted = new ArrayList<>(norm.keySet());
        sorted.sort((a, b) -> {
            int c = a.nm().compareTo(b.nm());
            return c != 0 ? c : a.addr().compareTo(b.addr());
        });

        // 1차 — 🔴 이름이 맞는 쪽이 먼저 자리를 가져간다(후보 수와 무관).
        //        MIN 이 먼저 집어가면 이름이 맞는 원천이 밀려난다(2026-09-30 E2E 실측 사고).
        for (SrcKey key : sorted) {
            String na = norm.get(key)[0];
            String nn = norm.get(key)[1];
            List<Long> cands = byAddr.getOrDefault(na, List.of());
            List<Long> nameMatched = cands.stream()
                    .filter(id -> !taken.containsKey(id))
                    .filter(id -> nn != null && nn.equals(ourName(id)))
                    .toList();
            if (nameMatched.size() == 1) {
                assign(out, taken, key, nameMatched.get(0), "ADDR", cands.size(), cands.size() > 1, na, nn);
            }
        }

        // 2차 — 남은 것: 주소 후보 중 아직 안 쓰인 것에서 MIN(pklt_id)
        for (SrcKey key : sorted) {
            if (out.containsKey(key)) {
                continue;
            }
            String na = norm.get(key)[0];
            String nn = norm.get(key)[1];
            List<Long> cands = byAddr.getOrDefault(na, List.of());
            Long free = cands.stream().filter(id -> !taken.containsKey(id)).findFirst().orElse(null);
            if (free != null) {
                assign(out, taken, key, free, "ADDR", cands.size(), cands.size() > 1, na, nn);
            }
        }

        // 3차 — 주소로 못 붙은 것: 이름 폴백(유일할 때만) / 사유 확정
        for (SrcKey key : sorted) {
            if (out.containsKey(key)) {
                continue;
            }
            String na = norm.get(key)[0];
            String nn = norm.get(key)[1];
            List<Long> addrCands = byAddr.getOrDefault(na, List.of());
            List<Long> nameCands = byName.getOrDefault(nn, List.of());
            if (!addrCands.isEmpty()) {
                // 주소 후보는 있었지만 전부 다른 원천 주차장이 가져갔다 — 먼저 온 것을 덮지 않는다.
                out.put(key, new Match(null, null, addrCands.size(), true, false, "DUP_TARGET", na, nn));
            } else if (nameCands.size() == 1 && !taken.containsKey(nameCands.get(0))) {
                assign(out, taken, key, nameCands.get(0), "NM", 1, false, na, nn);
            } else if (nameCands.size() > 1) {
                out.put(key, new Match(null, null, nameCands.size(), true, false, "NM_AMBIGUOUS", na, nn));
            } else {
                out.put(key, new Match(null, null, nameCands.size(), false, false, "ADDR_NM_BOTH_MISS", na, nn));
            }
        }

        long resolved = out.values().stream().filter(Match::resolved).count();
        log.info("원천 주차장 배정 — 전체 {}건 · 해석 {}건 · 미해석 {}건", out.size(), resolved, out.size() - resolved);
        return out;
    }

    private void assign(Map<SrcKey, Match> out, Map<Long, SrcKey> taken, SrcKey key, long pkltId,
                        String step, int candCnt, boolean ambiguous, String na, String nn) {
        taken.put(pkltId, key);
        out.put(key, new Match(pkltId, step, candCnt, ambiguous, false, null, na, nn));
    }

    /** 우리 표의 정규화 이름 — 후보 id 로 되찾는다(이름 일치 판정용). */
    private String ourName(long pkltId) {
        for (Map.Entry<String, List<Long>> e : byName.entrySet()) {
            if (e.getValue().contains(pkltId)) {
                return e.getKey();
            }
        }
        return null;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        return null; // 종료 상태를 바꾸지 않는다.
    }

    /**
     * 한 원천 행의 주차장을 해석한다.
     *
     * <p>🔴 {@code DUP_TARGET} 의 판정 키는 원천 주차장 정체성 {@code (pklt_nm, pklt_addr)} 다 —
     * <b>행이 아니다.</b> Reader 가 월 단위로 순회하므로 같은 주차장이 한 실행에 최대 15행(월)으로
     * 흘러온다. <b>같은 주차장의 다른 달은 정상 경로</b>이며 절대 스킵하지 않는다.
     */
    public Match resolve(PkltUtztnOriginRow row) {
        SrcKey key = SrcKey.of(row);
        Match decided = decisions.get(key);
        if (decided == null) {
            // 배정 목록에 없다 — 월별 수집 중에 원천에 주차장이 새로 생긴 경우다(착수 시 목록에 없었다).
            String na = normAddr(row.pkltAddr());
            String nn = normName(row.pkltNm());
            return new Match(null, null, 0, false, false, "ADDR_NM_BOTH_MISS", na, nn);
        }
        // 면수 대조는 행 단위다(월마다 달라질 수 있다) — 경고 기록에만 쓴다.
        Integer ours = decided.pkltId() == null ? null : cpcty.get(decided.pkltId());
        boolean cpctyDiffers = ours != null && ours != row.pkltCpcty();
        return new Match(decided.pkltId(), decided.mtchStp(), decided.candCnt(), decided.ambiguous(),
                cpctyDiffers, decided.failRsn(), decided.normAddr(), decided.normNm());
    }
}
