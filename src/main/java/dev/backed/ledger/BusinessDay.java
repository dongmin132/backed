package dev.backed.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** The shop's current business date, held in the single business_day row. */
@Service
public class BusinessDay {

    private final JdbcClient jdbc;

    public BusinessDay(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate current() {
        return jdbc.sql("select business_date from business_day where id")
                .query(LocalDate.class)
                .single();
    }

    /** The date after the current one; corrections are booked on it. Does not change anything. */
    public LocalDate next() {
        return current().plusDays(1);
    }

    /** Moves the business date forward one day and returns the new date. */
    @Transactional
    public LocalDate advance() {
        return jdbc.sql("update business_day set business_date = business_date + 1 where id returning business_date")
                .query(LocalDate.class)
                .single();
    }
}
