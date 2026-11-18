package jeju.datahub.batch.acdnt;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jeju.datahub.batch.acdnt.api.PkltAcdntOriginRow;
import jeju.datahub.batch.pblpklt.PkltNameAddrNormalizer;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 원천 순번을 주차장 PK로 쓰지 않는다. 유일한 이름/주소 후보만 내부 FK로 해석한다. */
@Component
public class PkltAcdntPkltIdResolver implements StepExecutionListener {
    private final JdbcTemplate jdbc;
    private Map<String, List<Long>> byAddress = Map.of();
    private Map<String, List<Long>> byName = Map.of();
    private Map<Long, String> names = Map.of();
    private Map<Long, String> addresses = Map.of();
    private java.util.Set<Long> activeIds = java.util.Set.of();
    public PkltAcdntPkltIdResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void beforeStep(StepExecution step) {
        Map<String, List<Long>> addresses = new HashMap<>();
        Map<String, List<Long>> nameMap = new HashMap<>();
        Map<Long, String> nameOf = new HashMap<>();
        Map<Long, String> addressOf = new HashMap<>();
        java.util.Set<Long> active = new java.util.HashSet<>();
        // 소멸 주차장도 과거 사고의 유효한 FK 대상이다.
        jdbc.query("SELECT pklt_id, pklt_nm, pklt_addr, extsh_dt FROM parking.tb_pblpklt", rs -> {
            long id = rs.getLong("pklt_id");
            String address = normalizedAddress(rs.getString("pklt_addr"));
            String name = PkltNameAddrNormalizer.normName(rs.getString("pklt_nm"));
            if (rs.getTimestamp("extsh_dt") == null) active.add(id);
            if (address != null && !address.isBlank()) {
                addresses.computeIfAbsent(address, k -> new ArrayList<>()).add(id);
                addressOf.put(id, address);
            }
            if (name != null && !name.isBlank()) {
                nameMap.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
                nameOf.put(id, name);
            }
        });
        byAddress = addresses;
        byName = nameMap;
        names = nameOf;
        this.addresses = addressOf;
        activeIds = active;
    }
    public long resolve(PkltAcdntOriginRow row) {
        String address = normalizedAddress(row.pkltAddr());
        String name = PkltNameAddrNormalizer.normName(row.pkltNm());
        var candidates = byAddress.getOrDefault(address, List.of());
        if (!candidates.isEmpty()) {
            var matched = candidates.stream().filter(id -> name != null && name.equals(names.get(id))).toList();
            if (matched.size() == 1) return matched.get(0);
            Long active = uniqueActiveDuplicate(matched.isEmpty() ? candidates : matched);
            if (active != null) return active;
            if (candidates.size() == 1) return candidates.get(0);
        } else candidates = byName.getOrDefault(name, List.of());
        if (candidates.size() == 1) return candidates.get(0);
        Long active = uniqueActiveDuplicate(candidates);
        if (active != null) return active;
        // 스킵 후 더 최근 사고가 커밋되면 이 사고가 증분 하한 밖으로 밀린다. 실패시켜 누락을 막는다.
        throw new IllegalStateException("사고이력 주차장 " + (candidates.isEmpty() ? "미발견" : "후보 모호")
                + " — origin_pk=" + row.originPk() + ", 후보 " + candidates.size() + "건. 이름·주소를 보완하세요.");
    }

    /** 이름·주소가 모두 같은 중복 사본 중 현재 유효한 행이 유일할 때만 대표로 선택한다. */
    private Long uniqueActiveDuplicate(List<Long> candidates) {
        if (candidates.size() < 2
                || candidates.stream().anyMatch(id -> names.get(id) == null || addresses.get(id) == null)
                || candidates.stream().map(names::get).distinct().count() != 1
                || candidates.stream().map(addresses::get).distinct().count() != 1) return null;
        var active = candidates.stream().filter(activeIds::contains).toList();
        return active.size() == 1 ? active.get(0) : null;
    }

    static String normalizedAddress(String value) {
        if (value == null) return null;
        String address = value.trim().replaceFirst("^(제주특별자치도|제주도)\\s+", "")
                .replaceFirst("^(제주시|서귀포시)\\s+", "");
        return PkltNameAddrNormalizer.normAddr(address).replaceAll("\\s+", " ");
    }
}
