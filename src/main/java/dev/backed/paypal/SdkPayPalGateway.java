package dev.backed.paypal;

import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.exceptions.ErrorException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.Address;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.CaptureOrderInput;
import com.paypal.sdk.models.CapturedPayment;
import com.paypal.sdk.models.CardRequest;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.CreateOrderInput;
import com.paypal.sdk.models.ErrorDetails;
import com.paypal.sdk.models.GetCapturedPaymentInput;
import com.paypal.sdk.models.GetOrderInput;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersCapture;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PaymentSource;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.PurchaseUnitRequest;
import com.paypal.sdk.models.Refund;
import com.paypal.sdk.models.RefundCapturedPaymentInput;
import com.paypal.sdk.models.RefundRequest;
import com.paypal.sdk.models.SellerPayableBreakdown;
import com.paypal.sdk.models.SellerReceivableBreakdown;
import dev.backed.ledger.Money;
import io.apimatic.core.exceptions.AuthValidationException;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link PayPalGateway} on the PayPal Java Server SDK (Orders v2 and Payments v2, sandbox).
 *
 * <p>Retries are off at every layer: the SDK gets an OkHttp client of ours with no retry
 * interceptor and {@code retryOnConnectionFailure(false)}, so a POST is never silently sent twice.
 * The caller decides about retries from the exception type:
 * <ul>
 *   <li>4xx business error: {@link PayPalDeclinedException} with PayPal's issue and debug id</li>
 *   <li>5xx, 408, 409, 429, connection refused, TLS or DNS failure, OAuth token failure:
 *       {@link PayPalUnavailableException}</li>
 *   <li>read timeout or connection dropped after the request went out, on a write:
 *       {@link PayPalOutcomeUnknownException}</li>
 * </ul>
 * A read (getOrder, getCapture) has no effect at PayPal, so its timeouts are reported as unavailable.
 */
public class SdkPayPalGateway implements PayPalGateway {

    private static final Logger log = LoggerFactory.getLogger(SdkPayPalGateway.class);

    static final String NOT_CONFIGURED =
            "PayPal credentials not configured: set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET (environment or .env)";

    private static final String JSON = "application/json";
    private static final String REPRESENTATION = "return=representation";
    private static final String USD = "USD";

    /** Billing address sent with the sandbox card. */
    public record BillingAddress(String line1, String city, String state, String postalCode, String countryCode) {
    }

    /** Explicit HTTP timeouts. callTimeout bounds the whole exchange including the OAuth token call. */
    public record Timeouts(Duration connect, Duration read, Duration write, Duration call) {

        public static Timeouts defaults() {
            return new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(10),
                    Duration.ofSeconds(45));
        }
    }

    private final PaypalServerSdkClient client;
    private final BillingAddress billingAddress;

    /** Builds a sandbox client; with blank credentials every call fails with {@link #NOT_CONFIGURED}. */
    public SdkPayPalGateway(String clientId, String clientSecret, BillingAddress billingAddress, Timeouts timeouts) {
        this(clientId, clientSecret, billingAddress, httpClient(timeouts), Environment.SANDBOX);
    }

    SdkPayPalGateway(String clientId, String clientSecret, BillingAddress billingAddress, OkHttpClient http,
                     Environment environment) {
        this.billingAddress = Objects.requireNonNull(billingAddress, "billingAddress");
        if (isBlank(clientId) || isBlank(clientSecret)) {
            log.warn(NOT_CONFIGURED + ". The app starts, but every PayPal call will fail.");
            this.client = null;
            return;
        }
        this.client = new PaypalServerSdkClient.Builder()
                .environment(environment)
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(clientId.trim(), clientSecret.trim())
                        .build())
                .httpClientConfig(c -> c
                        .numberOfRetries(0)
                        .shouldRetryOnTimeout(false)
                        // false: use our OkHttp client as-is, so the SDK adds no retry interceptor or timeouts
                        .httpClientInstance(http, false))
                .build();
    }

    /** Our OkHttp client: explicit timeouts, no automatic retries or redirects. */
    public static OkHttpClient httpClient(Timeouts t) {
        return new OkHttpClient.Builder()
                .connectTimeout(t.connect())
                .readTimeout(t.read())
                .writeTimeout(t.write())
                .callTimeout(t.call())
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public boolean configured() {
        return isConfigured();
    }

    // ---- writes -------------------------------------------------------------------------------

    @Override
    public CaptureResult captureCard(String requestId, long amountCents, String customId, TestCard card,
                                     OrderCreated onOrderCreated) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(onOrderCreated, "onOrderCreated");
        PaypalServerSdkClient c = requireClient();
        OrderRequest body = new OrderRequest.Builder(CheckoutPaymentIntent.CAPTURE, List.of(
                new PurchaseUnitRequest.Builder(new AmountWithBreakdown.Builder(USD, Money.toDecimalString(amountCents))
                        .build())
                        .customId(customId)
                        .description("Prepaid balance top-up")
                        .build()))
                .paymentSource(new PaymentSource.Builder()
                        .card(new CardRequest.Builder()
                                .name(card.nameOnCard())
                                .number(card.number())
                                .expiry(card.expiry())
                                .securityCode(card.securityCode())
                                .billingAddress(address())
                                .build())
                        .build())
                .build();
        CreateOrderInput input = new CreateOrderInput.Builder(JSON, body)
                .paypalRequestId(requestId)
                .prefer(REPRESENTATION)
                .build();

        ApiResponse<Order> response = write("create order " + requestId,
                () -> c.getOrdersController().createOrder(input));
        Order order = response.getResult();
        String status = enumValue(order.getStatus());
        if ("APPROVED".equals(status)) {
            // Card orders normally complete on create; if PayPal leaves one APPROVED, capture it with a
            // request id derived from ours so a re-send of the whole top-up stays idempotent. The caller
            // stores the order id first: if the capture call fails, GET order still finds the money.
            onOrderCreated.orderCreated(order.getId());
            String captureRequestId = requestId + "-capture";
            CaptureOrderInput captureInput = new CaptureOrderInput.Builder(order.getId(), JSON)
                    .paypalRequestId(captureRequestId)
                    .prefer(REPRESENTATION)
                    .build();
            response = write("capture order " + order.getId(), () -> c.getOrdersController().captureOrder(captureInput));
            order = response.getResult();
            status = enumValue(order.getStatus());
        }
        OrdersCapture capture = firstCapture(order);
        if (capture == null) {
            throw new PayPalDeclinedException("PayPal order " + order.getId() + " has status " + status
                    + " and no capture", response.getStatusCode(), "ORDER_" + status, debugId(response));
        }
        String captureStatus = enumValue(capture.getStatus());
        if ("DECLINED".equals(captureStatus) || "FAILED".equals(captureStatus)) {
            String code = capture.getProcessorResponse() == null
                    ? null : enumValue(capture.getProcessorResponse().getResponseCode());
            throw new PayPalDeclinedException("PayPal capture " + capture.getId() + " is " + captureStatus
                    + (code == null ? "" : " (processor response " + code + ")"),
                    response.getStatusCode(), "CAPTURE_" + captureStatus, debugId(response));
        }
        SellerReceivableBreakdown breakdown = capture.getSellerReceivableBreakdown();
        return new CaptureResult(
                order.getId(),
                capture.getId(),
                captureStatus,
                cents(capture.getAmount()),
                breakdown == null ? null : centsOrNull(breakdown.getPaypalFee()),
                breakdown == null ? null : centsOrNull(breakdown.getNetAmount()),
                capture.getCustomId() != null ? capture.getCustomId() : customIdOf(order));
    }

    @Override
    public RefundResult refundCapture(String requestId, String captureId, long amountCents, String noteToPayer) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(captureId, "captureId");
        PaypalServerSdkClient c = requireClient();
        RefundRequest body = new RefundRequest.Builder()
                .amount(new com.paypal.sdk.models.Money.Builder(USD, Money.toDecimalString(amountCents)).build())
                .noteToPayer(noteToPayer)
                .build();
        RefundCapturedPaymentInput input = new RefundCapturedPaymentInput.Builder(captureId, JSON)
                .paypalRequestId(requestId)
                .prefer(REPRESENTATION)
                .body(body)
                .build();
        Refund refund = write("refund capture " + captureId + " " + requestId,
                () -> c.getPaymentsController().refundCapturedPayment(input)).getResult();
        SellerPayableBreakdown breakdown = refund.getSellerPayableBreakdown();
        return new RefundResult(
                refund.getId(),
                captureId,
                enumValue(refund.getStatus()),
                refund.getAmount() == null ? amountCents : cents(refund.getAmount()),
                breakdown == null ? null : centsOrNull(breakdown.getPaypalFee()),
                breakdown == null ? null : centsOrNull(breakdown.getNetAmount()),
                refund.getNoteToPayer() != null ? refund.getNoteToPayer() : noteToPayer);
    }

    // ---- reads --------------------------------------------------------------------------------

    @Override
    public OrderSnapshot getOrder(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        PaypalServerSdkClient c = requireClient();
        Order order = read("get order " + orderId,
                () -> c.getOrdersController().getOrder(new GetOrderInput.Builder(orderId).build())).getResult();
        List<CaptureSnapshot> captures = new ArrayList<>();
        List<RefundSnapshot> refunds = new ArrayList<>();
        if (order.getPurchaseUnits() != null) {
            for (PurchaseUnit unit : order.getPurchaseUnits()) {
                PaymentCollection payments = unit.getPayments();
                if (payments == null) {
                    continue;
                }
                if (payments.getCaptures() != null) {
                    for (OrdersCapture cap : payments.getCaptures()) {
                        SellerReceivableBreakdown b = cap.getSellerReceivableBreakdown();
                        captures.add(new CaptureSnapshot(
                                cap.getId(),
                                order.getId(),
                                enumValue(cap.getStatus()),
                                cents(cap.getAmount()),
                                b == null ? null : centsOrNull(b.getPaypalFee()),
                                b == null ? null : centsOrNull(b.getNetAmount()),
                                cap.getCustomId() != null ? cap.getCustomId() : unit.getCustomId(),
                                time(cap.getCreateTime())));
                    }
                }
                if (payments.getRefunds() != null) {
                    for (Refund r : payments.getRefunds()) {
                        refunds.add(refundSnapshot(r, onlyCaptureId(payments)));
                    }
                }
            }
        }
        return new OrderSnapshot(order.getId(), enumValue(order.getStatus()), customIdOf(order), captures, refunds);
    }

    @Override
    public CaptureSnapshot getCapture(String captureId) {
        Objects.requireNonNull(captureId, "captureId");
        PaypalServerSdkClient c = requireClient();
        CapturedPayment cap = read("get capture " + captureId,
                () -> c.getPaymentsController().getCapturedPayment(new GetCapturedPaymentInput.Builder(captureId)
                        .build())).getResult();
        SellerReceivableBreakdown b = cap.getSellerReceivableBreakdown();
        String orderId = cap.getSupplementaryData() == null || cap.getSupplementaryData().getRelatedIds() == null
                ? null : cap.getSupplementaryData().getRelatedIds().getOrderId();
        return new CaptureSnapshot(
                cap.getId(),
                orderId,
                enumValue(cap.getStatus()),
                cents(cap.getAmount()),
                b == null ? null : centsOrNull(b.getPaypalFee()),
                b == null ? null : centsOrNull(b.getNetAmount()),
                cap.getCustomId(),
                time(cap.getCreateTime()));
    }

    // ---- error classification -----------------------------------------------------------------

    @FunctionalInterface
    private interface Call<T> {
        ApiResponse<T> run() throws ApiException, IOException;
    }

    private <T> ApiResponse<T> write(String what, Call<T> call) {
        return execute(what, call, true);
    }

    private <T> ApiResponse<T> read(String what, Call<T> call) {
        return execute(what, call, false);
    }

    private <T> ApiResponse<T> execute(String what, Call<T> call, boolean write) {
        try {
            return call.run();
        } catch (ApiException e) {
            throw classify(what, e);
        } catch (IOException e) {
            throw classify(what, e, write);
        } catch (AuthValidationException e) {
            // The SDK swallows the OAuth token call's own failure and reports only this; the API call itself was not sent.
            throw new PayPalUnavailableException("PayPal OAuth token could not be obtained for " + what
                    + " (check credentials and connectivity): " + e.getMessage(), 0, null, e);
        }
    }

    static PayPalException classify(String what, ApiException e) {
        int status = e.getResponseCode();
        String debugId = null;
        String issue = null;
        String detail = e.getMessage();
        if (e instanceof ErrorException err) {
            debugId = err.getDebugId();
            List<ErrorDetails> details = err.getDetails();
            if (details != null && !details.isEmpty() && details.get(0).getIssue() != null) {
                issue = details.get(0).getIssue();
                if (details.get(0).getDescription() != null) {
                    detail = details.get(0).getDescription();
                }
            } else {
                issue = err.getName();
            }
            if (err.getMessageField() != null && detail == null) {
                detail = err.getMessageField();
            }
        }
        String message = "PayPal " + what + " failed with HTTP " + status
                + (issue == null ? "" : " " + issue) + (detail == null ? "" : ": " + detail)
                + (debugId == null ? "" : " (debug id " + debugId + ")");
        // 408, 409 (same request id still in progress) and 429 are not business refusals: try again later.
        if (status >= 500 || status == 408 || status == 409 || status == 429) {
            return new PayPalUnavailableException(message, status, debugId, e);
        }
        return new PayPalDeclinedException(message, status, issue, debugId, e);
    }

    static PayPalException classify(String what, IOException e, boolean write) {
        String message = "PayPal " + what + ": " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " " + e.getMessage());
        if (neverSent(e) || !write) {
            return new PayPalUnavailableException(message, 0, null, e);
        }
        return new PayPalOutcomeUnknownException(message + " (the request may have reached PayPal)", e);
    }

    /** True when the failure happened before the request could reach PayPal. */
    private static boolean neverSent(IOException e) {
        if (e instanceof ConnectException || e instanceof UnknownHostException
                || e instanceof NoRouteToHostException || e instanceof SSLHandshakeException) {
            return true;
        }
        if (e instanceof SocketTimeoutException) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            return m.contains("connect");
        }
        return false;
    }

    // ---- mapping helpers ----------------------------------------------------------------------

    private PaypalServerSdkClient requireClient() {
        if (client == null) {
            throw new PayPalUnavailableException(NOT_CONFIGURED, 0, null);
        }
        return client;
    }

    private Address address() {
        return new Address.Builder(billingAddress.countryCode())
                .addressLine1(billingAddress.line1())
                .adminArea2(billingAddress.city())
                .adminArea1(billingAddress.state())
                .postalCode(billingAddress.postalCode())
                .build();
    }

    private static OrdersCapture firstCapture(Order order) {
        if (order.getPurchaseUnits() == null) {
            return null;
        }
        for (PurchaseUnit unit : order.getPurchaseUnits()) {
            if (unit.getPayments() != null && unit.getPayments().getCaptures() != null
                    && !unit.getPayments().getCaptures().isEmpty()) {
                return unit.getPayments().getCaptures().get(0);
            }
        }
        return null;
    }

    private static String customIdOf(Order order) {
        if (order.getPurchaseUnits() == null || order.getPurchaseUnits().isEmpty()) {
            return null;
        }
        return order.getPurchaseUnits().get(0).getCustomId();
    }

    /** The capture id when a purchase unit has exactly one capture, used when a refund carries no "up" link. */
    private static String onlyCaptureId(PaymentCollection payments) {
        return payments.getCaptures() != null && payments.getCaptures().size() == 1
                ? payments.getCaptures().get(0).getId() : null;
    }

    private static RefundSnapshot refundSnapshot(Refund r, String fallbackCaptureId) {
        SellerPayableBreakdown b = r.getSellerPayableBreakdown();
        String captureId = captureIdFromLinks(r.getLinks());
        return new RefundSnapshot(
                r.getId(),
                captureId != null ? captureId : fallbackCaptureId,
                enumValue(r.getStatus()),
                cents(r.getAmount()),
                b == null ? null : centsOrNull(b.getPaypalFee()),
                b == null ? null : centsOrNull(b.getNetAmount()),
                r.getNoteToPayer(),
                time(r.getCreateTime()));
    }

    /** The refund's "up" link points at /v2/payments/captures/{id}. */
    static String captureIdFromLinks(List<LinkDescription> links) {
        if (links == null) {
            return null;
        }
        for (LinkDescription link : links) {
            if ("up".equals(link.getRel()) && link.getHref() != null) {
                String href = link.getHref();
                int at = href.indexOf("/captures/");
                if (at >= 0) {
                    String rest = href.substring(at + "/captures/".length());
                    int end = rest.indexOf('/');
                    return end < 0 ? rest : rest.substring(0, end);
                }
            }
        }
        return null;
    }

    private static long cents(com.paypal.sdk.models.Money money) {
        if (money == null || money.getValue() == null) {
            throw new PayPalOutcomeUnknownException("PayPal response has no amount");
        }
        if (money.getCurrencyCode() != null && !USD.equals(money.getCurrencyCode())) {
            throw new IllegalStateException("Only USD is supported, PayPal returned " + money.getCurrencyCode());
        }
        return Money.fromDecimalString(money.getValue());
    }

    private static Long centsOrNull(com.paypal.sdk.models.Money money) {
        return money == null || money.getValue() == null ? null : cents(money);
    }

    private static OffsetDateTime time(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String enumValue(Object sdkEnum) {
        if (sdkEnum == null) {
            return null;
        }
        return sdkEnum.toString();
    }

    private static String debugId(ApiResponse<?> response) {
        com.paypal.sdk.http.Headers headers = response.getHeaders();
        if (headers == null) {
            return null;
        }
        for (String name : headers.names()) {
            if ("paypal-debug-id".equalsIgnoreCase(name)) {
                return headers.value(name);
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
