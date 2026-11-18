package jeju.datahub.batch.collect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.batch.item.file.separator.DefaultRecordSeparatorPolicy;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;

/** CSV 원문을 보존하며 DHub JSON 행과 같은 매핑 경로로 보낸다. BOM, 따옴표, 쉼표, 줄바꿈을 지원한다. */
public final class ParkingCsvSource {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ParkingCsvSource() { }

    public static List<JsonNode> read(String path) {
        try (var reader = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("주차장 CSV 헤더가 없습니다.");
            DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
            String[] columns = tokenizer.tokenize(header.replaceFirst("^\\uFEFF", "")).getValues();
            tokenizer.setNames(columns);
            var separator = new DefaultRecordSeparatorPolicy();
            List<JsonNode> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String record = line;
                while (!separator.isEndOfRecord(record)) {
                    line = reader.readLine();
                    if (line == null) throw new IllegalArgumentException("주차장 CSV 따옴표가 닫히지 않았습니다.");
                    record = separator.preProcess(record) + line;
                }
                if (record.isBlank()) continue;
                String[] values = tokenizer.tokenize(separator.postProcess(record)).getValues();
                ObjectNode row = MAPPER.createObjectNode();
                for (int i = 0; i < columns.length; i++) row.put(columns[i], values[i]);
                rows.add(row);
            }
            return rows;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("주차장 CSV를 읽을 수 없습니다.", e);
        }
    }
}
