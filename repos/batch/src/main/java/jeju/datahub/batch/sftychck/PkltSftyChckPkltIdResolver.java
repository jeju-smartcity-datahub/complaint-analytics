package jeju.datahub.batch.sftychck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.pblpklt.PkltNameAddrNormalizer;
import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/**
 * 원천 안전점검 행 → 우리 {@code parking.tb_pblpklt.pklt_id} 해석 + 자연키 중복 판정.
 *
 * <pre>
 * ① 키 경로      원천 pklt_id 가 있으면 = 공영주차장 데이터셋의 pklt_id = 우리 origin_pk 로 보고 찾는다.
 *                찾은 주차장의 정규화 이름 <b>또는</b> 정규화 주소가 원천과 같아야 통과(검증). 못 찾거나 검증 실패면 ②.
 * ② 이름·주소    1단 ADDR_NM  정규화 주소 후보 중 정규화 이름이 같은 것이 유일
 *                2단 ADDR     정규화 주소 후보가 유일
 *                3단 NM       정규화 주소 후보가 0 이고 정규화 이름 후보가 유일
 *                그 밖에 후보 2개 이상 → PKLT_AMBIGUOUS(후보 수) · 0개 → PKLT_NOT_FOUND
 * </pre>
 * 정규화는 이용패턴과 같은 공용 {@link PkltNameAddrNormalizer}(읍면 접두 제거 주소 · 괄호·공백 제거 이름)다.
 *
 * <h2>🔴 후보가 여럿이면 붙이지 않는다 — 이용패턴과 다른 점</h2>
 * 이용패턴({@code PkltUtztnPkltIdResolver})은 주소 후보 여럿이면 {@code MIN(pklt_id)} 를 주고, 원천 주차장끼리
 * 같은 {@code pklt_id} 를 다투지 않게 <b>착수 시 일괄 배정</b>(taken)한다. 여기서는 둘 다 쓰지 않는다 —
 * 틀린 주차장에 점검 결과가 붙으면 투자우선순위 점수가 FK 위반도 없이 <b>조용히 틀린다.</b>
 * 후보가 여럿이면 무조건 스킵하므로 판정이 행 단위로 닫히고 <b>순서 의존이 없다</b>(선배정이 필요 없다).
 * 실측(로컬): 4,514행 중 4,502행 정확 · 12행은 이름·주소가 완전히 같은 주차장 4쌍이라 원리적으로 구분 불가 → 스킵.
 *
 * <h2>🔴 {@code origin_pk} 는 {@code VARCHAR(40)} 이다</h2>
 * 원천 {@code pklt_id} 는 int64 로 오므로 {@link String#valueOf(Object)} 로 바꿔 <b>문자열로</b> 비교한다
 * (마이그레이션 48 · 70). 숫자로 캐스팅해 비교하지 않는다 — 원천 키가 숫자가 아닌 행이 섞여 있어도 맵 적재가 죽지 않는다.
 *
 * <h2>🔴 맵·키 집합의 수명 — {@code beforeStep()} 에서 새로 만든다</h2>
 * 싱글턴 빈이라 필드를 재사용하면 Job 실행 간 살아남는다. 특히 <b>본 자연키 집합</b>이 남으면 재실행 때 전량이
 * {@code DUP_KEY} 다. 그래서 자연키 집합도 Processor 가 아니라 여기서 맵과 함께 초기화한다 — Processor 를 따로
 * {@code StepExecutionListener} 로 만들면 등록을 빠뜨리기 쉽다.
 * <p>🔴 그러려면 Step 빌더에 {@code .listener(pkltIdResolver)} 로 <b>명시 등록</b>돼야 한다 — 위치는 체인의
 * <b>맨 마지막</b>이다({@code StepExecutionListener} 오버로드가 {@code SimpleStepBuilder} 를 돌려준다).
 *
 * <p>소멸 행({@code extsh_dt IS NOT NULL})도 후보에 넣는다 — 이용패턴과 같은 이유다(빼면 주차장이 소멸 처리되는
 * 순간 과거 점검 이력까지 미해석으로 바뀐다. FK 는 소멸 행도 허용한다).
 */
@Component
public class PkltSftyChckPkltIdResolver implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PkltSftyChckPkltIdResolver.class);

    static final String LOAD_SQL = "SELECT pklt_id, origin_pk, pklt_nm, pklt_addr, extsh_dt FROM parking.tb_pblpklt";

    /** 해석 경로 — Step 종료 시 경로별 건수를 남긴다. */
    public enum Path { KEY, ADDR_NM, ADDR, NM }

    /** 해석 결과 — 붙었으면 {@code pkltId}·{@code path} 가, 못 붙었으면 {@code failRsn} 이 있다. */
    public record Match(Long pkltId, Path path, int candCnt, String failRsn) {
        public boolean resolved() {
            return pkltId != null;
        }
    }

    /** 자연키 — {@code uq_tb_pklt_sfty_chck (pklt_id, chck_ymd, sfty_chck_type_cd)} 와 같아야 한다. */
    record NaturalKey(long pkltId, String chckYmd, String sftyChckTypeCd) { }

    private final JdbcTemplate jdbcTemplate;

    /** 우리 origin_pk → pklt_id (① 키 경로). */
    private Map<String, List<Long>> byOriginPk = Map.of();
    /** 정규화 주소 → 오름차순 pklt_id 목록 (② 1·2단 후보). */
    private Map<String, List<Long>> byAddr = Map.of();
    private Set<Long> activeIds = Set.of();
    /** 정규화 이름 → 오름차순 pklt_id 목록 (② 3단 후보). */
    private Map<String, List<Long>> byName = Map.of();
    /** pklt_id → 정규화 이름 (① 검증 · ② 1단 이름 일치). */
    private Map<Long, String> nameById = Map.of();
    /** pklt_id → 정규화 주소 (① 검증). */
    private Map<Long, String> addrById = Map.of();
    /** 이번 실행에서 이미 채택한 자연키 — 처음 1건만 채택한다(DUP_KEY). */
    private Set<NaturalKey> seenKeys = ConcurrentHashMap.newKeySet();

    /** 경로별 해석 건수 — 🔒 값이 아니라 건수만 남긴다. */
    private final Map<Path, AtomicLong> pathCounts = new EnumMap<>(Path.class);
    /** ① 키 경로를 시도했지만 못 찾았거나 검증에 실패해 ② 로 넘어간 건수. */
    private final AtomicLong keyFallbackCnt = new AtomicLong();
    private final AtomicLong ambiguousCnt = new AtomicLong();
    private final AtomicLong notFoundCnt = new AtomicLong();

    public PkltSftyChckPkltIdResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        resetCounts();
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        Map<String, List<Long>> originMap = new HashMap<>();
        Map<String, List<Long>> addrMap = new HashMap<>();
        Set<Long> active = new java.util.HashSet<>();
        Map<String, List<Long>> nameMap = new HashMap<>();
        Map<Long, String> nameOf = new HashMap<>();
        Map<Long, String> addrOf = new HashMap<>();

        jdbcTemplate.query(LOAD_SQL, rs -> {
            long id = rs.getLong("pklt_id");
            if (rs.getTimestamp("extsh_dt") == null) active.add(id);
            String originPk = rs.getString("origin_pk");
            String na = PkltNameAddrNormalizer.normAddr(rs.getString("pklt_addr"));
            String nn = PkltNameAddrNormalizer.normName(rs.getString("pklt_nm"));
            if (originPk != null) {
                originMap.computeIfAbsent(originPk, k -> new ArrayList<>()).add(id);
            }
            // 🔴 빈 정규화 값은 후보 키로 쓰지 않는다 — 쓰면 주소·이름이 빈 원천 행이 빈 값 주차장 아무에게나 붙는다.
            if (hasText(na)) {
                addrMap.computeIfAbsent(na, k -> new ArrayList<>()).add(id);
                addrOf.put(id, na);
            }
            if (hasText(nn)) {
                nameMap.computeIfAbsent(nn, k -> new ArrayList<>()).add(id);
                nameOf.put(id, nn);
            }
        });
        addrMap.values().forEach(Collections::sort);
        nameMap.values().forEach(Collections::sort);

        this.activeIds = Set.copyOf(active);
        this.byOriginPk = originMap;
        this.byAddr = addrMap;
        this.byName = nameMap;
        this.nameById = nameOf;
        this.addrById = addrOf;
        this.seenKeys = ConcurrentHashMap.newKeySet();
        resetCounts();
        log.info("안전점검 주차장 후보 맵 적재 — origin_pk {}키 · 정규화 주소 {}키 · 정규화 이름 {}키",
                originMap.size(), addrMap.size(), nameMap.size());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // 🔒 건수만 — 이름·주소·키 값은 찍지 않는다.
        log.info("안전점검 주차장 해석 경로별 건수 — KEY {} · ADDR_NM {} · ADDR {} · NM {} · 키 검증 실패→② {} · 모호 {} · 미발견 {}",
                pathCounts.get(Path.KEY), pathCounts.get(Path.ADDR_NM), pathCounts.get(Path.ADDR),
                pathCounts.get(Path.NM), keyFallbackCnt, ambiguousCnt, notFoundCnt);
        return null; // 종료 상태를 바꾸지 않는다.
    }

    /** 한 원천 행의 주차장을 해석한다(D2). 행 단위로 닫힌 판정이라 순서에 좌우되지 않는다. */
    public Match resolve(PkltSftyChckOriginRow row) {
        String na = PkltNameAddrNormalizer.normAddr(row.pkltAddr());
        String nn = PkltNameAddrNormalizer.normName(row.pkltNm());

        // ① 키 경로 — 원천 숫자를 문자열로 바꿔 origin_pk(VARCHAR)와 비교한다.
        if (row.pkltId() != null) {
            // 원천 ID 도 비유일이다. 후보가 여럿이면 임의의 한 행을 택하지 않고 주소·이름으로 해석한다.
            List<Long> candidates = byOriginPk.getOrDefault(String.valueOf(row.pkltId()), List.of());
            if (candidates.size() == 1) {
                Long id = candidates.get(0);
                if (hasText(na) ? sameText(na, addrById.get(id)) : sameText(nn, nameById.get(id))) {
                    return hit(id, Path.KEY, 1);
                }
            }
            keyFallbackCnt.incrementAndGet();
        }

        // ② 이름·주소 경로
        List<Long> addrCands = preferActiveObservationCandidates(hasText(na) ? byAddr.getOrDefault(na, List.of()) : List.of(), row);
        if (!addrCands.isEmpty()) {
            List<Long> nameMatched = addrCands.stream()
                    .filter(id -> sameText(nn, nameById.get(id)))
                    .toList();
            if (nameMatched.size() == 1) {
                return hit(nameMatched.get(0), Path.ADDR_NM, addrCands.size());   // 1단
            }
            if (addrCands.size() == 1) {
                return hit(addrCands.get(0), Path.ADDR, 1);                       // 2단
            }
            // 🔴 주소 후보 여럿 — 이름으로도 하나로 못 좁혔다. 추측하지 않는다(MIN 배정 금지).
            //    후보 수는 좁힐 수 있었던 만큼(이름 일치가 2개 이상이면 그 수)을 남긴다.
            int cnt = nameMatched.size() >= 2 ? nameMatched.size() : addrCands.size();
            return ambiguous(cnt);
        }

        // 3단 — 주소 후보 0 일 때만 이름으로 줍는다.
        List<Long> nameCands = preferActiveObservationCandidates(hasText(nn) ? byName.getOrDefault(nn, List.of()) : List.of(), row);
        if (nameCands.size() == 1) {
            return hit(nameCands.get(0), Path.NM, 1);
        }
        if (nameCands.size() > 1) {
            return ambiguous(nameCands.size());
        }
        notFoundCnt.incrementAndGet();
        return new Match(null, null, 0, PkltSftyChckSkippableException.PKLT_NOT_FOUND);
    }

    private List<Long> preferActiveObservationCandidates(List<Long> candidates, PkltSftyChckOriginRow row) {
        if (row.chckYmd() != null && !row.chckYmd().isBlank()) return candidates;
        List<Long> current = candidates.stream().filter(activeIds::contains).toList();
        return current.isEmpty() ? candidates : current;
    }

    /**
     * 이번 실행에서 처음 보는 자연키면 등록하고 {@code true}, 이미 본 키면 {@code false}(→ {@code DUP_KEY}).
     *
     * <p>🔴 Processor 가 <b>다른 모든 검증을 통과한 뒤 맨 마지막에</b> 부른다 — 앞에서 부르면 실패할 행이 키를 선점해
     * 뒤의 정상 행이 {@code DUP_KEY} 로 떨어진다. "처음" 은 keyset 정렬 순서(원천 {@code sfty_chck_id} 오름차순) 기준이다.
     */
    public boolean markIfFirst(long pkltId, String chckYmd, String sftyChckTypeCd) {
        return seenKeys.add(new NaturalKey(pkltId, chckYmd, sftyChckTypeCd));
    }

    private Match hit(long id, Path path, int candCnt) {
        pathCounts.get(path).incrementAndGet();
        return new Match(id, path, candCnt, null);
    }

    private Match ambiguous(int candCnt) {
        ambiguousCnt.incrementAndGet();
        return new Match(null, null, candCnt, PkltSftyChckSkippableException.PKLT_AMBIGUOUS);
    }

    private void resetCounts() {
        for (Path p : Path.values()) {
            pathCounts.put(p, new AtomicLong());
        }
        keyFallbackCnt.set(0);
        ambiguousCnt.set(0);
        notFoundCnt.set(0);
    }

    /** 경로별 해석 건수(이번 실행) — 테스트·진단용. */
    long pathCount(Path path) {
        return pathCounts.get(path).get();
    }

    private static boolean sameText(String a, String b) {
        return hasText(a) && a.equals(b);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }
}
