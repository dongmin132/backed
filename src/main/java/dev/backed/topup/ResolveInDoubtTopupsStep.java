package dev.backed.topup;

import dev.backed.close.PreCloseStep;
import dev.backed.config.Actors;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Close step 1 for top-ups: ask PayPal about every IN_DOUBT top-up before reconciling. */
@Component
@Order(10)
public class ResolveInDoubtTopupsStep implements PreCloseStep {

    private final TopupService topups;

    public ResolveInDoubtTopupsStep(TopupService topups) {
        this.topups = topups;
    }

    @Override
    public String name() {
        return "Resolve in-doubt top-ups";
    }

    @Override
    public int run() {
        return topups.resolveAll(Actors.CLOSE);
    }
}
