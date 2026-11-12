package dev.backed.customer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Repository
public class Customers {

    private static final String COLUMNS = "id, name, email, token, card_ref";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public Customers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> byToken(String token) {
        return jdbc.sql("select " + COLUMNS + " from customers where token = :token")
                .param("token", token)
                .query(Customer.class)
                .optional();
    }

    public Optional<Customer> byId(long id) {
        return jdbc.sql("select " + COLUMNS + " from customers where id = :id")
                .param("id", id)
                .query(Customer.class)
                .optional();
    }

    public List<Customer> all() {
        return jdbc.sql("select " + COLUMNS + " from customers order by id")
                .query(Customer.class)
                .list();
    }

    /** Creates a customer with a zero balance and a random token. */
    @Transactional
    public Customer create(String name, String email, String cardRef) {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        String token = "c-" + HexFormat.of().formatHex(bytes);
        long id = jdbc.sql("""
                        insert into customers (name, email, token, card_ref) values (:name, :email, :token, :cardRef)
                        returning id
                        """)
                .param("name", name)
                .param("email", email)
                .param("token", token)
                .param("cardRef", cardRef == null ? "visa" : cardRef)
                .query(Long.class)
                .single();
        jdbc.sql("insert into customer_balances (customer_id, balance_cents) values (:id, 0)")
                .param("id", id)
                .update();
        return byId(id).orElseThrow();
    }
}
