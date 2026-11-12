package dev.backed.paypal;

/** Base of the classified PayPal failures. See {@link PayPalGateway} for what each subclass means. */
public abstract class PayPalException extends RuntimeException {

    private final int httpStatus;
    private final String issue;
    private final String debugId;

    protected PayPalException(String message, int httpStatus, String issue, String debugId, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.issue = issue;
        this.debugId = debugId;
    }

    /** HTTP status, or 0 when no response arrived. */
    public int httpStatus() {
        return httpStatus;
    }

    /** PayPal's issue code such as INSTRUMENT_DECLINED, or null. */
    public String issue() {
        return issue;
    }

    /** PayPal-Debug-Id of the failed call, or null. */
    public String debugId() {
        return debugId;
    }
}
