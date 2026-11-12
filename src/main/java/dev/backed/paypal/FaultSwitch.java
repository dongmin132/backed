package dev.backed.paypal;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory demo fault flags. Each flag is one-shot: arming it affects the next matching PayPal
 * call only, and {@link #consume} clears it atomically. {@link FaultInjectingPayPalGateway}
 * applies them.
 */
@Component
public class FaultSwitch {

    public enum Fault {
        DROP_NEXT_CAPTURE_RESPONSE("Drop the response of the next capture after PayPal has accepted it"),
        FAIL_NEXT_REFUND_WITH_5XX("Fail the next refund with a 5xx before it reaches PayPal"),
        DECLINE_NEXT_REFUND("Decline the next refund with a 4xx before it reaches PayPal");

        private final String label;

        Fault(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Map<Fault, Boolean> armed = new ConcurrentHashMap<>();

    public void arm(Fault fault) {
        armed.put(fault, true);
    }

    public void disarm(Fault fault) {
        armed.remove(fault);
    }

    public boolean isArmed(Fault fault) {
        return armed.containsKey(fault);
    }

    /** True if the fault was armed; it is disarmed in the same step so only one caller sees it. */
    public boolean consume(Fault fault) {
        return armed.remove(fault) != null;
    }

    public void reset() {
        armed.clear();
    }

    /** Every fault with whether it is armed, in declaration order. */
    public Map<Fault, Boolean> snapshot() {
        Map<Fault, Boolean> out = new EnumMap<>(Fault.class);
        for (Fault f : Fault.values()) {
            out.put(f, armed.containsKey(f));
        }
        return out;
    }
}
