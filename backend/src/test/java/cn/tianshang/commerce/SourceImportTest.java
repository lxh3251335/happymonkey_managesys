package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import cn.tianshang.commerce.importing.SourceImporter;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class SourceImportTest extends ApiTestSupport {

    @Autowired
    SourceImporter importer;

    @TempDir
    Path directory;

    @Test
    void importIsIdempotentAndPreservesUnknownCosts() throws Exception {
        Path seed = directory.resolve("source.json");
        Files.writeString(
            seed,
            """
            {"batch":"test-import","summary":{"records":1},"records":[{
              "customer":"历史测试客户","project":"小象测试门店","brand":"小象",
              "orderDate":"2026-01-01","name":"电箱","specification":"650*850*250","model":"mini",
              "quantity":"2","amount":"100.01","cost":null,"source":"fixture!2","rawSource":"source!2"
            }]}
            """
        );
        importer.importSeed(seed);
        importer.importSeed(seed);
        var rows = getJson("/api/quotes");
        assertThat(rows.size()).isEqualTo(1);
        var quote = rows.get(0);
        assertThat(quote.path("status").asText()).isEqualTo("HISTORICAL");
        assertThat(quote.path("amount").decimalValue()).isEqualByComparingTo("100.01");
        assertThat(quote.path("cost").isNull()).isTrue();
        assertThat(quote.path("profit").isNull()).isTrue();
        assertThat(getJson("/api/cash/ledger").size()).isZero();
        var item = getJson("/api/quotes/" + quote.path("id").asText()).path("items").get(0);
        assertThat(item.path("source").asText()).contains("fixture!2");
    }
}
