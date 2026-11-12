package dev.backed.customer;

/**
 * A shop customer. Customers do not log in; token is the secret part of their private link
 * /c/{token}. cardRef names the sandbox test card used for their top-ups.
 */
public record Customer(long id, String name, String email, String token, String cardRef) {
}
