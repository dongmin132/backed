package dev.backed.config;

import java.security.Principal;

/**
 * Actor names recorded in created_by, approved_by and run_by. Controllers take the actor from the
 * request's Principal and pass it down as a String; services never look it up from a thread-local,
 * so the same code works from schedulers and webhooks, which pass one of the system actors.
 */
public final class Actors {

    public static final String SCHEDULER = "system:scheduler";
    public static final String WEBHOOK = "system:webhook";
    public static final String CLOSE = "system:close";

    private Actors() {
    }

    /** The signed-in user's name. Use in controller methods that declare a Principal parameter. */
    public static String of(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new IllegalStateException("No signed-in user for this request");
        }
        return principal.getName();
    }

    /** The actor for something a customer did from their private page. */
    public static String customer(long customerId) {
        return "customer:" + customerId;
    }
}
