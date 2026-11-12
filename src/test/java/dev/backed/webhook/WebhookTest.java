package dev.backed.webhook;

import dev.backed.close.CloseFixtures;
import dev.backed.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WebhookTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    WebhookService webhooks;

    @Test
    void sameWebhookDeliveredTwiceIsProcessedOnce() throws Exception {
        String body = CloseFixtures.captureCompletedEvent("WH-1", "CAPTURE-1", 2_500);

        mvc.perform(post("/webhooks/paypal").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.detail").value(containsString("not in the ledger")));
        OffsetDateTime processedAt = jdbc.sql("select processed_at from webhook_events where event_id = 'WH-1'")
                .query(OffsetDateTime.class).single();

        mvc.perform(post("/webhooks/paypal").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(jdbc.sql("select count(*) from webhook_events").query(Integer.class).single()).isEqualTo(1);
        assertThat(processedAt).isNotNull();
        assertThat(jdbc.sql("select processed_at from webhook_events where event_id = 'WH-1'")
                .query(OffsetDateTime.class).single()).isEqualTo(processedAt);
        assertThat(jdbc.sql("select resource_id from webhook_events").query(String.class).single()).isEqualTo("CAPTURE-1");
    }

    @Test
    void concurrentDeliveriesOfOneEventAreProcessedOnce() throws Exception {
        String body = CloseFixtures.captureRefundedEvent("WH-RACE", "REFUND-9", "CAPTURE-9", 1_000);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<WebhookService.Receipt>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return webhooks.receive(body);
                }));
            }
            List<WebhookService.Outcome> outcomes = new ArrayList<>();
            for (Future<WebhookService.Receipt> r : results) {
                outcomes.add(r.get(30, TimeUnit.SECONDS).outcome());
            }
            assertThat(outcomes).filteredOn(o -> o == WebhookService.Outcome.PROCESSED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == WebhookService.Outcome.DUPLICATE).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select count(*) from webhook_events").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from webhook_events where processed_at is not null")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void refundEventNamesItsCapture() {
        String body = CloseFixtures.captureRefundedEvent("WH-R", "REFUND-1", "CAPTURE-7", 500);

        WebhookService.Receipt receipt = webhooks.receive(body);

        assertThat(receipt.outcome()).isEqualTo(WebhookService.Outcome.PROCESSED);
        assertThat(receipt.detail()).contains("REFUND-1").contains("CAPTURE-7").contains("not in the ledger");
        assertThat(WebhookEvents.captureIdOf(WebhookEvents.CAPTURE_REFUNDED, body)).isEqualTo("CAPTURE-7");
    }

    @Test
    void malformedDeliveriesAreRejectedWithoutStoring() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/paypal").contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("select count(*) from webhook_events").query(Integer.class).single()).isZero();
    }

    @Test
    void staffCanListEvents() throws Exception {
        webhooks.receive(CloseFixtures.captureCompletedEvent("WH-LIST", "CAPTURE-L", 100));

        mvc.perform(get("/close/webhooks").with(user("maker").roles("MAKER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WH-LIST")))
                .andExpect(content().string(containsString("PAYMENT.CAPTURE.COMPLETED")));
        mvc.perform(get("/close/webhooks").with(user("clerk").roles("CLERK")))
                .andExpect(status().isForbidden());
    }
}
