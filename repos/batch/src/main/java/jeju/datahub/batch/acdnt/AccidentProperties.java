package jeju.datahub.batch.acdnt;

import jeju.datahub.batch.cvlcpt.api.DhubSql;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dhub.acdnt")
public class AccidentProperties {
    private String tableId = "";
    private String sourceTable = "";
    private int pageSize = 1000;
    public String getTableId() { return tableId; }
    public void setTableId(String value) { tableId = value; }
    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String value) { sourceTable = value; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int value) { pageSize = value; }
    public void validate() {
        if (tableId == null || tableId.isBlank())
            throw new IllegalStateException("DHUB_ACDNT_TABLE_ID에 파이프라인 출력 데이터셋 ID를 설정하세요.");
        DhubSql.table(sourceTable);
        DhubSql.limit(pageSize);
    }
}
