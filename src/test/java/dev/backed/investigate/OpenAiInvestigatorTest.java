package dev.backed.investigate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI adapter against a stub Responses API on 127.0.0.1: request shape, response parsing,
 * refusals, the single retry and timeouts, without the network. No Spring context. The key used
 * here is a made-up test value.
 */
class OpenAiInvestigatorTest {

    private static final String TEST_KEY = "test-key-not-real";
    private static final String BUNDLE = "{\"break\":{\"kind\":\"UNBOOKED_REFUND\"},\"note\":\"Sorry about the cold coffee\"}";
    private static final String ANSWER = """
            {"treatment":"GOODWILL","evidence_quotes":["Sorry about the cold coffee"],
             "reasoning":"The note apologises for a bad coffee.","rejected_alternative":"BALANCE_RETURNED",
             "rejected_because":"Nobody asked for the balance back.","confidence":"HIGH",
             "customer_note":"Your balance is unchanged."}
            """;

    private record Stub(int status, String body, long delayMillis, Map<String, String> headers) {
        Stub(int status, String body) {
            this(status, body, 0, Map.of());
        }
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
    private final Queue<Stub> answers = new ConcurrentLinkedQueue<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-openai");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheSharedPromptAndAStrictJsonSchemaAndParsesTheAnswer() {
        answers.add(new Stub(200, completed(ANSWER)));

        InvestigatorOutput out = investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE);

        assertThat(out.treatment()).isEqualTo(Treatment.GOODWILL);
        assertThat(out.evidenceQuotes()).containsExactly("Sorry about the cold coffee");
        assertThat(out.rejectedAlternative()).isEqualTo(Treatment.BALANCE_RETURNED);
        assertThat(out.confidence()).isEqualTo("HIGH");
        assertThat(out.customerNote()).isEqualTo("Your balance is unchanged.");
        assertThat(out.model()).isEqualTo("gpt-6.1-sol-answered");

        assertThat(seen).hasSize(1);
        Seen request = seen.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/responses");
        // Compared, never printed.
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TEST_KEY);
        assertThat(request.header("Content-Type")).isEqualTo("application/json");

        JsonNode body = Json.tryParse(request.body()).orElseThrow();
        assertThat(body.path("model").asString()).isEqualTo("gpt-6.1-sol");
        assertThat(body.path("instructions").asString()).isEqualTo(InvestigatorPrompt.SYSTEM_PROMPT);
        assertThat(body.path("input").asString()).isEqualTo(InvestigatorPrompt.userMessage(BUNDLE));
        JsonNode format = body.path("text").path("format");
        assertThat(format.path("type").asString()).isEqualTo("json_schema");
        assertThat(format.path("name").asString()).isEqualTo(OpenAiInvestigator.SCHEMA_NAME);
        assertThat(format.path("strict").asBoolean()).isTrue();
        assertThat(format.path("schema")).isEqualTo(Json.MAPPER.valueToTree(InvestigatorPrompt.OUTPUT_SCHEMA));
        assertThat(format.path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.path("reasoning").path("effort").asString()).isEqualTo("medium");
        assertThat(body.path("store").asBoolean(true)).isFalse();
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(OpenAiInvestigator.MAX_OUTPUT_TOKENS);
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void blankEffortLeavesReasoningOut() {
        answers.add(new Stub(200, completed(ANSWER)));

        investigator("", Duration.ofSeconds(5)).investigate(BUNDLE);

        assertThat(Json.tryParse(seen.getFirst().body()).orElseThrow().has("reasoning")).isFalse();
    }

    @Test
    void aRefusalIsAClearFailure() {
        answers.add(new Stub(200, """
                {"id":"resp_1","object":"response","status":"completed","model":"gpt-6.1-sol",
                 "output":[{"type":"message","role":"assistant","status":"completed",
                            "content":[{"type":"refusal","refusal":"I'm sorry, I cannot assist with that request."}]}]}
                """));

        assertThatThrownBy(() -> investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI declined to answer: I'm sorry, I cannot assist with that request");
        assertThat(seen).hasSize(1);
    }

    @Test
    void malformedOrUnusableContentIsAClearFailure() {
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse(completed("not json at all"), "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI's answer is not a JSON object.");
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse(completed("{\"treatment\":\"REFUND_IT\"}"), "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI returned an unknown treatment.");
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse(completed("{\"evidence_quotes\":[]}"), "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI's answer has no treatment.");
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse("<html>bad gateway</html>", "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI's response is not JSON.");
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse("""
                {"status":"completed","model":"m","output":[{"type":"reasoning","summary":[]}]}
                """, "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI's answer has no text.");
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"model":"m",
                 "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\\"treat"}]}]}
                """, "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI's answer was cut off before it was complete.");
        assertThatThrownBy(() -> OpenAiInvestigator.parseResponse("""
                {"status":"failed","error":{"code":"server_error","message":"The server had an error."},"output":[]}
                """, "m"))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI could not produce an answer: The server had an error");

        // Through the HTTP path as well: a 200 whose text is not the contract's JSON.
        answers.add(new Stub(200, completed("Sure! The treatment is goodwill.")));
        assertThatThrownBy(() -> investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI's answer is not a JSON object.");
    }

    @Test
    void aRateLimitIsRetriedOnceThenSucceeds() {
        answers.add(new Stub(429, error("rate_limit_exceeded", "requests", "Rate limit reached for requests"),
                0, Map.of("Retry-After", "0")));
        answers.add(new Stub(200, completed(ANSWER)));

        InvestigatorOutput out = investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE);

        assertThat(out.treatment()).isEqualTo(Treatment.GOODWILL);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).body()).isEqualTo(seen.get(0).body());
    }

    @Test
    void aServerErrorIsRetriedAtMostOnce() {
        answers.add(new Stub(500, error("server_error", "server_error", "The server had an error processing your request.")));
        answers.add(new Stub(503, error("server_is_overloaded", "service_unavailable_error", "The engine is overloaded.")));
        answers.add(new Stub(200, completed(ANSWER)));

        assertThatThrownBy(() -> investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI API returned HTTP 503: The engine is overloaded (after one retry)");
        assertThat(seen).hasSize(2);
    }

    @Test
    void aQuotaLimitAndAClientErrorAreNotRetried() {
        answers.add(new Stub(429, error("insufficient_quota", "insufficient_quota",
                "You exceeded your current quota, please check your plan and billing details.")));
        assertThatThrownBy(() -> investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessageStartingWith("OpenAI API returned HTTP 429: You exceeded your current quota")
                .hasMessageContaining("billing or quota limit");
        assertThat(seen).hasSize(1);

        answers.clear();
        seen.clear();
        answers.add(new Stub(400, error("model_not_found", "invalid_request_error",
                "The model `gpt-nope` does not exist or you do not have access to it.")));
        assertThatThrownBy(() -> investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("OpenAI API returned HTTP 400: The model `gpt-nope` does not exist or you do not have access to it");
        assertThat(seen).hasSize(1);
    }

    @Test
    void anAuthenticationErrorNamesTheVariableAndNeverShowsTheKey() {
        answers.add(new Stub(401, error("invalid_api_key", "invalid_request_error",
                "Incorrect API key provided: sk-proj-abcd****************************wxyz. You can find your API key at"
                        + " https://platform.openai.com/account/api-keys.")));

        assertThatThrownBy(() -> investigator("medium", Duration.ofSeconds(5)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessageStartingWith("OpenAI API returned HTTP 401: Incorrect API key provided: sk-...")
                .hasMessageEndingWith("Check OPENAI_API_KEY.")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("abcd", "wxyz", TEST_KEY));
        assertThat(seen).hasSize(1);
    }

    @Test
    void aSlowAnswerTimesOutWithAClearMessageAndIsNotRetried() {
        answers.add(new Stub(200, completed(ANSWER), 3_000, Map.of()));

        long started = System.nanoTime();
        assertThatThrownBy(() -> investigator("medium", Duration.ofMillis(300)).investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage("The OpenAI API did not answer within 300 ms.");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
        assertThat(seen).hasSize(1);
    }

    @Test
    void anUnreachableApiIsAClearFailure() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        OpenAiInvestigator investigator = new OpenAiInvestigator(TEST_KEY, "gpt-6.1-sol", "medium",
                "http://127.0.0.1:" + port + "/v1", Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ZERO);

        assertThatThrownBy(() -> investigator.investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessageStartingWith("Could not connect to the OpenAI API");
    }

    @Test
    void withoutAKeyItIsNotConfiguredAndCallsNothing() {
        OpenAiInvestigator investigator = new OpenAiInvestigator(" ", "gpt-6.1-sol", "medium", baseUrl(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO);

        assertThat(investigator.configured()).isFalse();
        assertThat(investigator.provider()).isEqualTo("openai");
        assertThatThrownBy(() -> investigator.investigate(BUNDLE))
                .isInstanceOf(InvestigatorException.class)
                .hasMessage(ProposalService.NOT_CONFIGURED);
        assertThat(seen).isEmpty();
    }

    private OpenAiInvestigator investigator(String effort, Duration readTimeout) {
        return new OpenAiInvestigator(TEST_KEY, "gpt-6.1-sol", effort, baseUrl(), Duration.ofSeconds(2), readTimeout,
                Duration.ofMillis(10));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** A completed Responses API answer whose assistant message holds the given text. */
    private static String completed(String text) {
        var root = Json.object();
        root.put("id", "resp_test");
        root.put("object", "response");
        root.put("status", "completed");
        root.put("model", "gpt-6.1-sol-answered");
        var output = root.putArray("output");
        output.addObject().put("type", "reasoning").put("id", "rs_1").putArray("summary");
        var message = output.addObject();
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("status", "completed");
        message.putArray("content").addObject().put("type", "output_text").put("text", text).putArray("annotations");
        return Json.write(root);
    }

    private static String error(String code, String type, String message) {
        var root = Json.object();
        var error = root.putObject("error");
        error.put("message", message);
        error.put("type", type);
        error.putNull("param");
        error.put("code", code);
        return Json.write(root);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), Map.copyOf(ex.getRequestHeaders()), body));
        Stub stub = answers.poll();
        if (stub == null) {
            stub = new Stub(500, error("server_error", "server_error", "no stub answer left"));
        }
        if (stub.delayMillis() > 0) {
            try {
                Thread.sleep(stub.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        stub.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        try {
            ex.sendResponseHeaders(stub.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        } finally {
            ex.close();
        }
    }
}
