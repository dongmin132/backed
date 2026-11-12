package dev.backed.customer;

import java.time.OffsetDateTime;

public record CustomerMessage(long id, long customerId, String body, OffsetDateTime createdAt) {
}
