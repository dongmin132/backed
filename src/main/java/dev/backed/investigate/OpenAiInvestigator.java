package dev.backed.investigate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The investigator backed by the OpenAI Responses API ({@code POST /v1/responses}), called with the
 * JDK HTTP client: one call per break, the shared prompt, Structured Outputs with the shared JSON
 * schema in strict mode, no tools, nothing stored on OpenAI's side ({@code store: false}).
 *
 * <p>Explicit connect and read timeouts. At most one retry, and only on a 429 that is a rate limit
 * (not a billing or quota limit) or on a 5xx. A refusal, a cut-off answer or unusable content is an
 * {@link InvestigatorException} with a message a person can read; the API key never appears in one.
 * Created by {@link InvestigatorConfig} when the provider is openai.
 */
public class OpenAiInvestigator implements Investigator, DisposableBean {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final String SCHEMA_NAME = "break_treatment";
    static final int MAX_OUTPUT_TOKENS = 8000;
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(10);
    private static final int MAX_ERROR_TEXT = 300;
    private static final Pattern SECRET = Pattern.compile("\\bsk-[A-Za-z0-9_*\\-]{3,}");    /** 429 codes and types that mean money or a usage cap, where retrying cannot help. */
    private static final Set<String> NOT_RETRYABLE_429 = Set.of("insufficient_quota", "credit_balance_exhausted",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded", "billing_hard_limit_reached");

    private static final Logger log = LoggerFactory.getLogger(OpenAiInvestigator.class);

    private final String apiKey;
    private final String model;
    private final String effort;
    private final URI endpoint;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration retryDelay;
    private volatile HttpClient http;

    /**
     * @param effort     reasoning effort sent as reasoning.effort; blank leaves it out (for models without reasoning)
     * @param retryDelay wait before the single retry when the answer has no Retry-After header
     */
    public OpenAiInvestigator(String apiKey, String model, String effort, String baseUrl,
                              Duration connectTimeout, Duration readTimeout, Duration retryDelay) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
        this.effort = effort == null ? "" : effort.strip();
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/responses");
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.retryDelay = retryDelay;
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String provider() {
        return AiProvider.OPENAI.id();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public InvestigatorOutput investigate(String evidenceBundleJson) {
        if (!configured()) {
            throw new InvestigatorException(ProposalService.NOT_CONFIGURED);
        }
        HttpRequest request = request(requestBody(evidenceBundleJson));
        HttpResponse<String> response = send(request);
        if (retryable(response)) {
            Duration wait = retryWait(response);
            log.warn("OpenAI answered HTTP {}; retrying once in {} ms", response.statusCode(), wait.toMillis());
            sleep(wait);
            HttpResponse<String> second = send(request);
            if (second.statusCode() / 100 != 2) {
                throw new InvestigatorException(httpError(second) + " (after one retry)");
            }
            response = second;
        }
        if (response.statusCode() / 100 != 2) {
            throw new InvestigatorException(httpError(response));
        }
        return parseResponse(response.body(), model);
    }

    /** The JSON body of the Responses API call. */
    String requestBody(String evidenceBundleJson) {
        ObjectNode body = Json.object();
        body.put("model", model);
        body.put("instructions", InvestigatorPrompt.SYSTEM_PROMPT);
        body.put("input", InvestigatorPrompt.userMessage(evidenceBundleJson));
        ObjectNode format = body.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", SCHEMA_NAME);
        format.put("strict", true);
        format.set("schema", Json.MAPPER.valueToTree(InvestigatorPrompt.OUTPUT_SCHEMA));
        if (!effort.isEmpty()) {
            body.putObject("reasoning").put("effort", effort);
        }
        body.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        body.put("store", false);
        return Json.MAPPER.writeValueAsString(body);
    }

    HttpRequest request(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            builder.header("Authorization", "Bearer " + apiKey);
        } catch (IllegalArgumentException e) {
            // The JDK's message repeats the whole header value, key included, so it is not kept as the cause.
            throw new InvestigatorException("OPENAI_API_KEY contains a character that cannot be sent in an HTTP"
                    + " header, such as a line break. Check its value.");
        }
        return builder.build();
    }

    /**
     * Reads a Responses API answer: the output_text of the assistant message, parsed against the
     * shared contract. A refusal, an incomplete or failed response, or content that is not the
     * contract's JSON is an InvestigatorException.
     */
    static InvestigatorOutput parseResponse(String body, String requestedModel) {
        JsonNode root = Json.tryParse(body).filter(JsonNode::isObject)
                .orElseThrow(() -> new InvestigatorException("OpenAI's response is not JSON."));
        String status = root.path("status").asString("");
        StringBuilder text = new StringBuilder();
        String refusal = null;
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asString(""))) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                String type = part.path("type").asString("");
                if ("refusal".equals(type)) {
                    refusal = part.path("refusal").asString("");
                } else if ("output_text".equals(type)) {
                    text.append(part.path("text").asString(""));
                }
            }
        }
        if (refusal != null) {
            throw new InvestigatorException("OpenAI declined to answer"
                    + (refusal.isBlank() ? "." : ": " + clean(refusal)));
        }
        if ("incomplete".equals(status)) {
            String reason = root.path("incomplete_details").path("reason").asString("");
            throw new InvestigatorException("max_output_tokens".equals(reason)
                    ? "OpenAI's answer was cut off before it was complete."
                    : "OpenAI stopped before finishing its answer" + (reason.isEmpty() ? "." : " (" + reason + ")."));
        }
        if ("failed".equals(status) || root.path("error").isObject()) {
            String message = root.path("error").path("message").asString("");
            throw new InvestigatorException("OpenAI could not produce an answer"
                    + (message.isBlank() ? "." : ": " + clean(message)));
        }
        if (!status.isEmpty() && !"completed".equals(status)) {
            throw new InvestigatorException("OpenAI's response is " + clean(status) + ", not completed.");
        }
        if (text.isEmpty()) {
            throw new InvestigatorException("OpenAI's answer has no text.");
        }
        String answeredBy = root.path("model").asString("");
        return InvestigatorPrompt.parse(text.toString(), answeredBy.isBlank() ? requestedModel : answeredBy, "OpenAI");
    }

    private HttpResponse<String> send(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> pending =
                client().sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            // A hard bound on the whole exchange, body included; the request timeout covers the headers.
            return pending.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw readTimedOut(e);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new InvestigatorException("The OpenAI call was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof HttpConnectTimeoutException) {
                throw new InvestigatorException("Could not connect to the OpenAI API within "
                        + seconds(connectTimeout) + ".", cause);
            }
            if (cause instanceof HttpTimeoutException) {
                throw readTimedOut(cause);
            }
            if (cause instanceof ConnectException) {
                throw new InvestigatorException("Could not connect to the OpenAI API.", cause);
            }
            if (cause instanceof IOException) {
                throw new InvestigatorException("The OpenAI API could not be reached: "
                        + clean(String.valueOf(cause.getMessage())), cause);
            }
            throw new InvestigatorException("The OpenAI call failed: " + clean(String.valueOf(cause.getMessage())), cause);
        }
    }

    private InvestigatorException readTimedOut(Throwable cause) {
        return new InvestigatorException("The OpenAI API did not answer within " + seconds(readTimeout) + ".", cause);
    }

    /** A rate limit or a server error; a 429 for money or a usage cap is final. */
    static boolean retryable(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 500) {
            return true;
        }
        if (status != 429) {
            return false;
        }
        JsonNode error = Json.tryParse(response.body()).map(n -> n.path("error")).orElse(null);
        if (error == null) {
            return true;
        }
        return !NOT_RETRYABLE_429.contains(error.path("code").asString(""))
                && !NOT_RETRYABLE_429.contains(error.path("type").asString(""));
    }

    private Duration retryWait(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .flatMap(OpenAiInvestigator::parseRetryAfter)
                .map(d -> d.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : d)
                .orElse(retryDelay);
    }

    /** Retry-After in seconds; an HTTP date is not used. */
    private static Optional<Duration> parseRetryAfter(String header) {
        try {
            double s = Double.parseDouble(header.strip());
            return s < 0 ? Optional.empty() : Optional.of(Duration.ofMillis((long) (s * 1000)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** "OpenAI API returned HTTP 401: Incorrect API key provided: sk-...", without the key. */
    static String httpError(HttpResponse<String> response) {
        int status = response.statusCode();
        String message = Json.tryParse(response.body())
                .map(n -> n.path("error").path("message").asString(""))
                .orElse("");
        StringBuilder out = new StringBuilder("OpenAI API returned HTTP ").append(status);
        if (!message.isBlank()) {
            out.append(": ").append(clean(message));
        }
        if (status == 401) {
            out.append(message.isBlank() ? "" : ".").append(" Check OPENAI_API_KEY.");
        } else if (status == 429 && !retryable(response)) {
            out.append(message.isBlank() ? "" : ".").append(" This is a billing or quota limit; check the OpenAI account's credit.");
        }
        return out.toString();
    }

    /** Removes anything shaped like an API key, line breaks, and overly long text. */
    static String clean(String text) {
        String s = SECRET.matcher(text == null ? "" : text).replaceAll("sk-...").replaceAll("\\s+", " ").strip();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.length() > MAX_ERROR_TEXT ? s.substring(0, MAX_ERROR_TEXT) + "..." : s;
    }

    private static String seconds(Duration d) {
        return d.toMillis() % 1000 == 0 ? d.toSeconds() + " s" : d.toMillis() + " ms";
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvestigatorException("The OpenAI call was interrupted.", e);
        }
    }

    private HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (this) {
                c = http;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .connectTimeout(connectTimeout)
                            .version(HttpClient.Version.HTTP_1_1)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    http = c;
                }
            }
        }
        return c;
    }

    @Override
    public void destroy() {
        HttpClient c = http;
        if (c != null) {
            c.close();
        }
    }
}
