package vn.sgodata.revenueintelligence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.test.context.SpringBootTest;
import vn.coreplatform.AbstractApiTest;
import vn.coreplatform.CorePlatformApplication;

@SpringBootTest(classes=CorePlatformApplication.class)
class RevenueIntelligenceApiTest extends AbstractApiTest {
  @Test void importsReconcilesAttributesAndBuildsDashboard() throws Exception {
    var token = adminToken();
    var key = suffix();
    var customers = "source_system,external_customer_id,display_name,phone,email,history_complete\n" +
        "CRM,CUST-" + key + ",Synthetic Customer,0900000001,customer-" + key + "@example.invalid,true\n";
    importCsv(token, "customers", "customers.csv", customers)
        .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedRows").value(1)).andExpect(jsonPath("$.duplicate").value(false));
    importCsv(token, "customers", "customers.csv", customers)
        .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));

    var orders = "source_system,external_order_id,customer_source,external_customer_id,ordered_at,status,business_model,currency,gross_revenue,discount_amount,refund_amount,source_channel\n" +
        "POS,ORDER-" + key + ",CRM,CUST-" + key + ",2026-05-01T09:00:00+07:00,COMPLETED,RETAIL,VND,1000000,50000,0,PAID_SOCIAL\n";
    importCsv(token, "orders", "orders.csv", orders).andExpect(status().isOk()).andExpect(jsonPath("$.acceptedRows").value(1));

    var spend = "source_system,external_id,spend_date,channel,campaign_external_id,campaign_name,currency,amount\n" +
        "META,SPEND-" + key + ",2026-05-01,PAID_SOCIAL,CAMP-" + key + ",Synthetic,VND,250000\n";
    importCsv(token, "ad-spend", "spend.csv", spend).andExpect(status().isOk());

    var touchpoints = "source_system,external_touchpoint_id,customer_source,external_customer_id,occurred_at,channel,campaign_external_id,utm_source,utm_medium\n" +
        "WEB,TP-" + key + ",CRM,CUST-" + key + ",2026-04-25T11:00:00+07:00,PAID_SOCIAL,CAMP-" + key + ",facebook,cpc\n";
    importCsv(token, "touchpoints", "touchpoints.csv", touchpoints).andExpect(status().isOk());

    mvc.perform(post("/api/v1/revenue-intelligence/attribution/rebuild?from=2026-05-01&to=2026-05-31").with(bearer(token)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.resultsWritten").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    mvc.perform(get("/api/v1/revenue-intelligence/reconciliation?from=2026-05-01&to=2026-05-31").with(bearer(token)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.variance").value(0.0));
    mvc.perform(get("/api/v1/revenue-intelligence/dashboard?from=2026-05-01&to=2026-05-31").with(bearer(token)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.kpis.netRevenue").value(org.hamcrest.Matchers.greaterThanOrEqualTo(950000.0)))
        .andExpect(jsonPath("$.channels[?(@.key == 'PAID_SOCIAL')]").isNotEmpty());
    mvc.perform(get("/api/v1/revenue-intelligence/customers").with(bearer(token)))
        .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.externalId == 'CUST-" + key + "')].emailMasked").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.containsString("***"))));
  }

  private org.springframework.test.web.servlet.ResultActions importCsv(String token, String dataset, String name, String content) throws Exception {
    var file = new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    return mvc.perform(multipart("/api/v1/revenue-intelligence/imports/" + dataset).file(file).with(bearer(token)));
  }
}
