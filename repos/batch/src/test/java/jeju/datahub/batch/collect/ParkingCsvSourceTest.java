package jeju.datahub.batch.collect;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jeju.datahub.batch.cvlcpt.api.*;
import jeju.datahub.batch.utztn.api.*;
import jeju.datahub.batch.sftychck.api.*;

class ParkingCsvSourceTest {
    @TempDir Path dir;
    @Test void quotedCommasNewlinesBomAndMissingMonthSurviveCsvMapping() throws Exception {
        Path csv=dir.resolve("usage.csv");
        Files.writeString(csv,"\uFEFFindex,pklt_nm,pklt_addr,msmt_ym,use_cnt,pklt_cpcty,use_rate\n1,\"이름\n두 줄\",\"주소 1, 2\",,14,94,14.89\n");
        var props=new DhubProperties(); props.setUtztnCsvPath(csv.toString());
        var query=mock(DhubQueryClient.class);
        var client=new DhubPkltUtztnApiClient(query,props);
        assertThat(client.fetchMonths()).containsExactly("");
        var row=client.fetchMonth("").get(0);
        assertThat(row.totYm()).isNull();
        assertThat(row.pkltAddr()).isEqualTo("주소 1, 2");
        assertThat(row.pkltNm()).contains("이름","두 줄");
        assertThat(row.useCnt()).isEqualTo(14);
        assertThat(row.srcJson()).contains("14.89");
        assertThat(client.countAll()).isEqualTo(1);
        verifyNoInteractions(query);
    }
    @Test void missingParkingIdDateAndTypeAndSourceDGradeAreNotFilledWithDefaults() throws Exception {
        Path csv=dir.resolve("safety.csv");
        Files.writeString(csv,"sfty_chck_id,pklt_id,chck_ymd,chck_prd_cd,sfty_chck_type_cd,sfty_chck_rslt_cd,rmrk_cn,pklt_nm,pklt_addr\n1,,,,,D,원문,,주소 1\n");
        var props=new DhubProperties(); props.setSftychckCsvPath(csv.toString());
        var query=mock(DhubQueryClient.class);
        var client=new DhubPkltSftyChckApiClient(query,props);
        assertThat(client.detectPkltIdColumn()).isTrue();
        var page=client.fetchPage(null,null,1000);
        var row=PkltSftyChckOriginRow.from(page.rows().get(0),true);
        assertThat(row.pkltId()).isNull();
        assertThat(row.chckYmd()).isNull();
        assertThat(row.sftyChckTypeCd()).isNull();
        assertThat(row.sftyChckRsltCd()).isEqualTo("D");
        verifyNoInteractions(query);
    }
    @Test void missingUsageNameUsesTheSameNullKeyInLotListAndOriginRow() throws Exception {
        Path csv=dir.resolve("unnamed.csv");
        Files.writeString(csv,"index,pklt_nm,pklt_addr,msmt_ym,use_cnt,pklt_cpcty,use_rate\n1,,주소 1,,0,10,0\n");
        var props=new DhubProperties(); props.setUtztnCsvPath(csv.toString());
        var client=new DhubPkltUtztnApiClient(mock(DhubQueryClient.class),props);
        assertThat(client.fetchLots().get(0)[0]).isNull();
        assertThat(client.fetchMonth("").get(0).pkltNm()).isNull();
    }
    @Test void targetedRetryReadsOnlyPreviouslyMissingSourceIds() throws Exception {
        Path csv=dir.resolve("retry.csv");
        Files.writeString(csv,"sfty_chck_id,pklt_id,chck_ymd,chck_prd_cd,sfty_chck_type_cd,sfty_chck_rslt_cd,rmrk_cn,pklt_nm,pklt_addr\n1,,,,,A,,가,주소 1\n2,,,,,D,,나,주소 2\n");
        var props=new DhubProperties();props.setSftychckCsvPath(csv.toString());
        props.setSftychckCsvIncludeIds(java.util.Set.of(2L));
        var client=new DhubPkltSftyChckApiClient(mock(DhubQueryClient.class),props);
        assertThat(client.count(null)).isEqualTo(1);
        assertThat(client.fetchPage(null,null,1000).rows().get(0).path("sfty_chck_id").asText()).isEqualTo("2");
    }

}
