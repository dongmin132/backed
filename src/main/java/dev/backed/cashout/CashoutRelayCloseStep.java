package dev.backed.cashout;

import dev.backed.close.PreCloseStep;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Before the books are closed, send every waiting cash-out leg once, including IN_DOUBT ones. */
@Component
@Order(200)
public class CashoutRelayCloseStep implements PreCloseStep {

    private final CashoutRelay relay;

    public CashoutRelayCloseStep(CashoutRelay relay) {
        this.relay = relay;
    }

    @Override
    public String name() {
        return "Cash-out refunds";
    }

    @Override
    public int run() {
        RelayReport report = relay.runOnce();
        return report.refunded() + report.failed();
    }
}
