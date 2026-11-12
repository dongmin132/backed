package dev.backed.paypal;

import com.paypal.sdk.Environment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SDK adapter against a stub PayPal on 127.0.0.1: the OkHttp client rewrites PayPal's sandbox
 * host to the stub, so request shape, response mapping, error classification and the absence of
 * retries are checked without the network. No Spring context.
 */
class SdkPayPalGatewayTest {

    private static final TestCard CARD = new TestCard("4111111111111111", "2030-12", "123", "Sandbox Visa");
    private static final SdkPayPalGateway.BillingAddress ADDRESS =
            new SdkPayPalGateway.BillingAddress("2211 N First Street", "San Jose", "CA", "95131", "US");

    private record Stub(int status, String body, long delayMillis) {
    }

    private record Seen(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }

    private HttpServer server;
    private final Map<String, Stub> routes = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-paypal");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
        routes.put("POST /v1/oauth2/token", new Stub(200,
                "{\"access_token\":\"stub-token\",\"token_type\":\"Bearer\",\"expires_in\":32400,\"scope\":\"x\"}", 0));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void captureSendsCardRequestIdAndRepresentationAndMapsFeeAndNet() {
        routes.put("POST /v2/checkout/orders", new Stub(201, completedOrder("COMPLETED"), 0));

        CaptureResult r = gateway(Duration.ofSeconds(5)).captureCard("req-1", 2_500, "topup-1", CARD);

        assertThat(r).isEqualTo(new CaptureResult("ORDER-1", "CAP-1", "COMPLETED", 2_500, 136L, 2_364L, "topup-1"));
        Seen order = only("POST /v2/checkout/orders");
        assertThat(order.header("PayPal-Request-Id")).isEqualTo("req-1");
        assertThat(order.header("Prefer")).isEqualTo("return=representation");
        assertThat(order.header("Authorization")).isEqualTo("Bearer stub-token");
        assertThat(order.body()).contains("\"intent\":\"CAPTURE\"", "\"custom_id\":\"topup-1\"",
                "\"value\":\"25.00\"", "\"currency_code\":\"USD\"", "\"number\":\"4111111111111111\"",
                "\"expiry\":\"2030-12\"", "\"security_code\":\"123\"", "\"billing_address\"",
                "\"postal_code\":\"95131\"");
    }

    @Test
    void businessErrorIsDeclinedWithIssueAndDebugIdAndIsNotRetried() {
        routes.put("POST /v2/checkout/orders", new Stub(422, """
                {"name":"UNPROCESSABLE_ENTITY","message":"The requested action could not be performed.",
                 "debug_id":"dbg-422","details":[{"issue":"INSTRUMENT_DECLINED",
                 "description":"The instrument presented was declined."}],"links":[]}
                """, 0));

        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).captureCard("req-1", 2_500, "t", CARD))
                .isInstanceOfSatisfying(PayPalDeclinedException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.issue()).isEqualTo("INSTRUMENT_DECLINED");
                    assertThat(e.debugId()).isEqualTo("dbg-422");
                });
        assertThat(count("POST /v2/checkout/orders")).isEqualTo(1);
    }

    @Test
    void declinedCaptureInsideA201IsDeclined() {
        routes.put("POST /v2/checkout/orders", new Stub(201, completedOrder("DECLINED"), 0));

        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).captureCard("req-1", 2_500, "t", CARD))
                .isInstanceOfSatisfying(PayPalDeclinedException.class,
                        e -> assertThat(e.issue()).isEqualTo("CAPTURE_DECLINED"));
    }

    @Test
    void serverErrorIsUnavailableAndIsNotRetried() {
        routes.put("POST /v2/checkout/orders", new Stub(503,
                "{\"name\":\"SERVICE_UNAVAILABLE\",\"message\":\"Try later\",\"debug_id\":\"dbg-503\"}", 0));

        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).captureCard("req-1", 2_500, "t", CARD))
                .isInstanceOfSatisfying(PayPalUnavailableException.class,
                        e -> assertThat(e.httpStatus()).isEqualTo(503));
        assertThat(count("POST /v2/checkout/orders")).as("the SDK must not retry").isEqualTo(1);
    }

    @Test
    void approvedOrderIdIsReportedBeforeTheCaptureCallThatFailsWith5xx() {
        routes.put("POST /v2/checkout/orders", new Stub(201, """
                {"id":"ORDER-1","status":"APPROVED","intent":"CAPTURE","purchase_units":[{"reference_id":"default",
                 "custom_id":"topup-1"}],"links":[]}
                """, 0));
        routes.put("POST /v2/checkout/orders/ORDER-1/capture", new Stub(503,
                "{\"name\":\"SERVICE_UNAVAILABLE\",\"message\":\"Try later\",\"debug_id\":\"dbg-503\"}", 0));
        List<String> reported = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).captureCard("req-1", 2_500, "t", CARD, reported::add))
                .isInstanceOfSatisfying(PayPalUnavailableException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(503);
                    assertThat(e.reachedPayPal()).isTrue();
                });
        assertThat(reported).containsExactly("ORDER-1");
        assertThat(only("POST /v2/checkout/orders/ORDER-1/capture").header("PayPal-Request-Id")).isEqualTo("req-1-capture");
    }

    @Test
    void readTimeoutOnAWriteIsOutcomeUnknownAndIsNotRetried() {
        routes.put("POST /v2/checkout/orders", new Stub(201, completedOrder("COMPLETED"), 1_500));

        assertThatThrownBy(() -> gateway(Duration.ofMillis(300)).captureCard("req-1", 2_500, "t", CARD))
                .isInstanceOf(PayPalOutcomeUnknownException.class);
        assertThat(count("POST /v2/checkout/orders")).isEqualTo(1);
    }

    @Test
    void readTimeoutOnAReadIsUnavailable() {
        routes.put("GET /v2/checkout/orders/ORDER-1", new Stub(200, completedOrder("COMPLETED"), 1_500));

        assertThatThrownBy(() -> gateway(Duration.ofMillis(300)).getOrder("ORDER-1"))
                .isInstanceOf(PayPalUnavailableException.class);
    }

    @Test
    void connectionRefusedIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        SdkPayPalGateway gateway = new SdkPayPalGateway("id", "secret", ADDRESS,
                http(Duration.ofSeconds(2), closedPort), Environment.SANDBOX);

        assertThatThrownBy(() -> gateway.captureCard("req-1", 2_500, "t", CARD))
                .isInstanceOfSatisfying(PayPalUnavailableException.class,
                        e -> assertThat(e.reachedPayPal()).as("nothing reached PayPal").isFalse());
    }

    @Test
    void missingCredentialsStartButEveryCallFailsClearly() {
        SdkPayPalGateway gateway = new SdkPayPalGateway("", " ", ADDRESS, SdkPayPalGateway.Timeouts.defaults());

        assertThat(gateway.isConfigured()).isFalse();
        assertThatThrownBy(() -> gateway.captureCard("req-1", 2_500, "t", CARD))
                .isInstanceOf(PayPalUnavailableException.class)
                .hasMessageContaining("PayPal credentials not configured");
        assertThatThrownBy(() -> gateway.refundCapture("req-2", "CAP-1", 100, null))
                .hasMessageContaining("PayPal credentials not configured");
        assertThatThrownBy(() -> gateway.getOrder("ORDER-1")).hasMessageContaining("PayPal credentials not configured");
        assertThatThrownBy(() -> gateway.getCapture("CAP-1")).hasMessageContaining("PayPal credentials not configured");
        assertThat(seen).isEmpty();
    }

    @Test
    void refundSendsRequestIdAndNoteAndMapsPayableBreakdown() {
        routes.put("POST /v2/payments/captures/CAP-1/refund", new Stub(201, refund("REF-1", "5.00", "0.10", "4.90"), 0));

        RefundResult r = gateway(Duration.ofSeconds(5)).refundCapture("req-r", "CAP-1", 500, "Sorry, $5 back");

        assertThat(r).isEqualTo(new RefundResult("REF-1", "CAP-1", "COMPLETED", 500, 10L, 490L, "Sorry, $5 back"));
        Seen req = only("POST /v2/payments/captures/CAP-1/refund");
        assertThat(req.header("PayPal-Request-Id")).isEqualTo("req-r");
        assertThat(req.body()).contains("\"note_to_payer\":\"Sorry, $5 back\"", "\"value\":\"5.00\"");
    }

    @Test
    void refundAboveRefundableIsDeclinedWithPayPalsIssue() {
        routes.put("POST /v2/payments/captures/CAP-1/refund", new Stub(422, """
                {"name":"UNPROCESSABLE_ENTITY","message":"The requested action could not be performed.",
                 "debug_id":"dbg-r","details":[{"issue":"REFUND_AMOUNT_EXCEEDED",
                 "description":"The refund amount must be less than or equal to the capture amount."}]}
                """, 0));

        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).refundCapture("req-r", "CAP-1", 99_999, null))
                .isInstanceOfSatisfying(PayPalDeclinedException.class,
                        e -> assertThat(e.issue()).isEqualTo("REFUND_AMOUNT_EXCEEDED"));
    }

    @Test
    void getOrderReturnsCapturesAndRefundsWithNotesAndCaptureIds() {
        String order = """
                {"id":"ORDER-1","status":"COMPLETED","intent":"CAPTURE","purchase_units":[{"reference_id":"default",
                 "custom_id":"topup-1","payments":{"captures":[%s],"refunds":[%s,%s]}}]}
                """.formatted(capture("COMPLETED"), refund("REF-1", "3.00", "0.00", "3.00"),
                refund("REF-2", "2.00", null, null));
        routes.put("GET /v2/checkout/orders/ORDER-1", new Stub(200, order, 0));

        OrderSnapshot snap = gateway(Duration.ofSeconds(5)).getOrder("ORDER-1");

        assertThat(snap.orderId()).isEqualTo("ORDER-1");
        assertThat(snap.customId()).isEqualTo("topup-1");
        assertThat(snap.captures()).singleElement().satisfies(c -> {
            assertThat(c.captureId()).isEqualTo("CAP-1");
            assertThat(c.feeCents()).isEqualTo(136L);
            assertThat(c.netCents()).isEqualTo(2_364L);
            assertThat(c.createTime()).isNotNull();
        });
        assertThat(snap.refundsOf("CAP-1")).extracting(RefundSnapshot::refundId).containsExactly("REF-1", "REF-2");
        assertThat(snap.refunds().get(0).noteToPayer()).isEqualTo("Sorry, $5 back");
        assertThat(snap.refunds().get(1).feeCents()).isNull();
    }

    @Test
    void getCaptureReturnsGrossFeeNetAndOrderId() {
        routes.put("GET /v2/payments/captures/CAP-1", new Stub(200, capture("PARTIALLY_REFUNDED")
                .replace("\"custom_id\"", "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"ORDER-1\"}},\"custom_id\""), 0));

        CaptureSnapshot c = gateway(Duration.ofSeconds(5)).getCapture("CAP-1");

        assertThat(c.status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(c.orderId()).isEqualTo("ORDER-1");
        assertThat(c.amountCents()).isEqualTo(2_500);
        assertThat(c.feeCents()).isEqualTo(136L);
        assertThat(c.netCents()).isEqualTo(2_364L);
    }

    @Test
    void notFoundIsDeclined() {
        routes.put("GET /v2/checkout/orders/NOPE", new Stub(404, """
                {"name":"RESOURCE_NOT_FOUND","message":"The specified resource does not exist.","debug_id":"dbg-404",
                 "details":[{"issue":"INVALID_RESOURCE_ID","description":"Specified resource ID does not exist."}]}
                """, 0));

        assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).getOrder("NOPE"))
                .isInstanceOfSatisfying(PayPalDeclinedException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(404);
                    assertThat(e.issue()).isEqualTo("INVALID_RESOURCE_ID");
                });
    }

    // ---- stub plumbing ------------------------------------------------------------------------

    private SdkPayPalGateway gateway(Duration readTimeout) {
        return new SdkPayPalGateway("stub-id", "stub-secret", ADDRESS,
                http(readTimeout, server.getAddress().getPort()), Environment.SANDBOX);
    }

    private static OkHttpClient http(Duration readTimeout, int port) {
        Interceptor toStub = chain -> {
            HttpUrl url = chain.request().url().newBuilder()
                    .scheme("http").host(InetAddress.getLoopbackAddress().getHostAddress()).port(port).build();
            return chain.proceed(chain.request().newBuilder().url(url).build());
        };
        return SdkPayPalGateway.httpClient(new SdkPayPalGateway.Timeouts(Duration.ofSeconds(2), readTimeout,
                        Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .newBuilder()
                .addInterceptor(toStub)
                .build();
    }

    private void handle(HttpExchange ex) throws IOException {
        String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
        Stub stub = routes.getOrDefault(key, new Stub(404, "{\"name\":\"RESOURCE_NOT_FOUND\"}", 0));
        if (stub.delayMillis() > 0) {
            try {
                Thread.sleep(stub.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Paypal-Debug-Id", "hdr-debug");
        try {
            ex.sendResponseHeaders(stub.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // the client gave up (timeout tests)
        } finally {
            ex.close();
        }
    }

    private Seen only(String key) {
        List<Seen> matching = seen.stream().filter(s -> (s.method() + " " + s.path()).equals(key)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private long count(String key) {
        return seen.stream().filter(s -> (s.method() + " " + s.path()).equals(key)).count();
    }

    private static String completedOrder(String captureStatus) {
        return """
                {"id":"ORDER-1","status":"COMPLETED","intent":"CAPTURE","purchase_units":[{"reference_id":"default",
                 "custom_id":"topup-1","payments":{"captures":[%s]}}],"links":[]}
                """.formatted(capture(captureStatus));
    }

    private static String capture(String status) {
        return """
                {"id":"CAP-1","status":"%s","amount":{"currency_code":"USD","value":"25.00"},"final_capture":true,
                 "seller_receivable_breakdown":{"gross_amount":{"currency_code":"USD","value":"25.00"},
                 "paypal_fee":{"currency_code":"USD","value":"1.36"},"net_amount":{"currency_code":"USD","value":"23.64"}},
                 "custom_id":"topup-1","create_time":"2026-11-12T10:00:00Z","update_time":"2026-11-12T10:00:00Z"}
                """.formatted(status);
    }

    private static String refund(String id, String value, String fee, String net) {
        String breakdown = fee == null ? "" : """
                ,"seller_payable_breakdown":{"gross_amount":{"currency_code":"USD","value":"%s"},
                 "paypal_fee":{"currency_code":"USD","value":"%s"},"net_amount":{"currency_code":"USD","value":"%s"}}
                """.formatted(value, fee, net);
        return """
                {"id":"%s","status":"COMPLETED","amount":{"currency_code":"USD","value":"%s"},
                 "note_to_payer":"Sorry, $5 back","create_time":"2026-11-12T11:00:00Z"%s,
                 "links":[{"href":"https://api.sandbox.paypal.com/v2/payments/refunds/%s","rel":"self","method":"GET"},
                          {"href":"https://api.sandbox.paypal.com/v2/payments/captures/CAP-1","rel":"up","method":"GET"}]}
                """.formatted(id, value, breakdown, id);
    }
}
