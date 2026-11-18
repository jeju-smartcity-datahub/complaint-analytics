package jeju.datahub.batch.cvlcpt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 실 어댑터와 같은 load_dt 구간 및 복합 커서 규약으로 샘플을 재생한다. */
@Component
@ConditionalOnProperty(name = "complaint.api.mode", havingValue = "mock")
public class MockComplaintApiClient implements ComplaintApiClient {

    private static final Logger log = LoggerFactory.getLogger(MockComplaintApiClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** load_dt와 행 해시로 정렬한 샘플. */
    private final List<ComplaintOriginRow> samples;

    public MockComplaintApiClient(
            @Value("${complaint.api.mock.sample:mock/complaint-sample.json}") String samplePath) {
        this.samples = load(samplePath);
        log.warn("⚠️ 민원 수집이 mock 모드입니다(complaint.api.mode=mock). 샘플 {}건을 재생합니다: {}",
                samples.size(), samplePath);
    }

    @Override
    public ComplaintPage fetch(DateRange range, String cursor, int size) {
        return fetch(new jeju.datahub.batch.collect.SourceLoadRange(range.fromInclusive().atStartOfDay(),
                range.toExclusive().atStartOfDay()), cursor, size);
    }

    @Override
    public ComplaintPage fetch(jeju.datahub.batch.collect.SourceLoadRange range, String cursor, int size) {
        DhubSql.limit(size);
        var after = jeju.datahub.batch.collect.SourceLoadCursor.decode(cursor);
        var page = samples.stream().distinct()
                .filter(row -> row.loadDt() == null || (row.loadDt().isBefore(range.toExclusive())
                        && (range.fromInclusive() == null || !row.loadDt().isBefore(range.fromInclusive()))))
                .sorted(Comparator.comparing(MockComplaintApiClient::cursorOf))
                .filter(row -> after == null || cursorOf(row).compareTo(after) > 0).limit(size).toList();
        return new ComplaintPage(page, page.size() == size,
                page.isEmpty() ? null : cursorOf(page.get(page.size()-1)).encode());
    }

    private static jeju.datahub.batch.collect.SourceLoadCursor cursorOf(ComplaintOriginRow row) {
        return new jeju.datahub.batch.collect.SourceLoadCursor(row.loadDt(),
                jeju.datahub.batch.collect.SourceFingerprint.hash(row.toString()));
    }

    private static List<ComplaintOriginRow> load(String samplePath) {
        try (InputStream input = new ClassPathResource(samplePath).getInputStream()) {
            JsonNode root = MAPPER.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!root.isArray()) {
                throw new IllegalStateException(
                        "mock 샘플이 JSON 배열이 아닙니다(실응답 rows 와 형태가 달라 검증 의미가 없다): " + samplePath);
            }
            List<ComplaintOriginRow> rows = new ArrayList<>(root.size());
            root.forEach(node -> rows.add(ComplaintOriginRow.fromApi(node)));
            rows.sort(Comparator.comparing(MockComplaintApiClient::cursorOf));
            return List.copyOf(rows);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(
                    "mock 샘플을 읽지 못했습니다: " + samplePath + " (complaint.api.mock.sample)", e);
        }
    }
}
