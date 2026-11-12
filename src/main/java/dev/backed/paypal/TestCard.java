package dev.backed.paypal;

/**
 * A sandbox test card used as the order's payment source.
 *
 * @param expiry YYYY-MM, the format PayPal expects
 */
public record TestCard(String number, String expiry, String securityCode, String nameOnCard) {

    @Override
    public String toString() {
        String last4 = number == null || number.length() < 4 ? "????" : number.substring(number.length() - 4);
        return "TestCard[****" + last4 + "]";
    }
}
