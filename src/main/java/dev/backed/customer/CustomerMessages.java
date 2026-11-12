package dev.backed.customer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Messages customers send to the shop from their private page. The investigator reads them. */
@Repository
public class CustomerMessages {

    public static final int MAX_LENGTH = 2000;

    private final JdbcClient jdbc;

    public CustomerMessages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long add(long customerId, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A message must be 1 to " + MAX_LENGTH + " characters");
        }
        return jdbc.sql("insert into customer_messages (customer_id, body) values (:id, :body) returning id")
                .param("id", customerId)
                .param("body", text)
                .query(Long.class)
                .single();
    }

    /** Newest first. */
    public List<CustomerMessage> recent(long customerId, int limit) {
        return jdbc.sql("""
                        select id, customer_id, body, created_at from customer_messages
                         where customer_id = :id order by created_at desc, id desc limit :limit
                        """)
                .param("id", customerId)
                .param("limit", limit)
                .query(CustomerMessage.class)
                .list();
    }
}
