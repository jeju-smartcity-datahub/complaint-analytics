package jeju.datahub.batch.crackdown.api;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import jeju.datahub.batch.collect.*;
import jeju.datahub.batch.cvlcpt.api.*;

/** 실 HTTP 전송으로 NULL/중복 원천 PK와 동일 시각의 페이지 경계를 검증한다. */
class DhubCrackdownKeysetPagingTest {
    static final ObjectMapper JSON=new ObjectMapper();
    HttpServer server;
    DhubProperties properties;
    final List<String> queries=new ArrayList<>();
    final AtomicBoolean truncated=new AtomicBoolean();
    final AtomicBoolean repeat=new AtomicBoolean();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/auth/login", ex->{
            String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"exp\":"+Instant.now().plusSeconds(3600).getEpochSecond()+"}").getBytes(StandardCharsets.UTF_8));
            respond(ex,JSON.createObjectNode().put("access_token","e30."+payload+".fixture").put("token_type","bearer"));
        });
        server.createContext("/api/v1/analytics/query", ex->{
            JsonNode request=JSON.readTree(ex.getRequestBody());String sql=request.path("query").asText();queries.add(sql);
            int after=0;var match=Pattern.compile("batch_row_key > '([a-f0-9]{64})'").matcher(sql);
            if(match.find() && !repeat.get()) after=new java.math.BigInteger(match.group(1),16).intValueExact();
            ArrayNode rows=JSON.createArrayNode();
            for(int n=after+1;n<=Math.min(after+2,5);n++) {
                ObjectNode row=rows.addObject();
                if(n%2==0) row.putNull("crdn_id"); else row.put("crdn_id",77);
                row.put("crdn_dt","2026-01-01 00:00:00").put("load_dt","2026-11-01 12:00:00")
                   .put("orgnl_cn","fixture-"+n).put("batch_row_key",String.format("%064x",n));
            }
            respond(ex,JSON.createObjectNode().put("truncated",truncated.get()).set("rows",rows));
        });
        server.start();properties=new DhubProperties();
        properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        properties.setEmail("test@example.com");properties.setPassword("fixture");
        properties.setCrackdownTableId("dataset.test");properties.setCrackdownSourceTable("`test`.`crackdown`");
        properties.setCrackdownPageSize(2);properties.setCrackdownDatePattern("yyyy-MM-dd HH:mm:ss[.SSS]");
    }
    @AfterEach void stop() {server.stop(0);}
    void respond(HttpExchange ex,ObjectNode body) throws java.io.IOException {
        byte[] bytes=JSON.writeValueAsBytes(body);ex.getResponseHeaders().set("Content-Type","application/json");
        ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
    }
    DhubCrackdownItemReader reader() {
        var query=new DhubQueryClient(new DhubAuthClient(properties),properties);
        return new DhubCrackdownItemReader(new DhubCrackdownApiClient(query,properties),properties,
            ()->new SourceLoadRange(null,LocalDateTime.parse("2026-11-15T00:00:00")));
    }
    @Test void allFiveRowsSurviveDuplicateAndNullIdsAcrossThreePages() {
        var reader=reader();var data=new ArrayList<CrackdownOriginRow>();CrackdownOriginRow row;
        while((row=reader.read())!=null) data.add(row);
        assertThat(data).hasSize(5);assertThat(data.stream().filter(r->r.originPk()==null)).hasSize(2);
        assertThat(data.stream().map(CrackdownOriginRow::srcHash).distinct()).hasSize(5);
        assertThat(queries).hasSize(3).allSatisfy(sql->assertThat(sql).doesNotContain("crdn_id >","OFFSET"));
        assertThat(queries.get(1)).contains("batch_row_key > '"+String.format("%064x",2)+"'");
    }
    @Test void truncatedResponseFails() {
        truncated.set(true);assertThatThrownBy(()->reader().read()).isInstanceOf(DhubApiException.class);
    }
    @Test void repeatedPageFails() {
        repeat.set(true);var reader=reader();reader.read();reader.read();
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }
}
