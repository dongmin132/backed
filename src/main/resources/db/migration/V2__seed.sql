-- Reference data and demo customers. Tests re-run this script after truncating every table,
-- so it must work on an empty schema and only insert.

insert into accounts (code, type, name) values
    ('PAYPAL_CASH', 'ASSET', 'Cash held at PayPal'),
    ('IN_DOUBT_RECEIVABLE', 'ASSET', 'Top-ups with unknown outcome'),
    ('CUSTOMER_PREPAID', 'LIABILITY', 'Customer prepaid balances'),
    ('SUSPENSE_IN_DOUBT', 'LIABILITY', 'Suspense for in-doubt top-ups'),
    ('CASHOUT_CLEARING', 'LIABILITY', 'Cash-outs in flight'),
    ('SALES_REVENUE', 'INCOME', 'Sales'),
    ('PAYPAL_FEES', 'EXPENSE', 'PayPal fees'),
    ('GOODWILL_EXPENSE', 'EXPENSE', 'Goodwill refunds');

insert into business_day (id, business_date) values (true, current_date);

insert into customers (name, email, token, card_ref) values
    ('Demo Customer A', 'customer-a@example.com', 'demo-a-7f3k9q2m', 'visa'),
    ('Demo Customer B', 'customer-b@example.com', 'demo-b-4p8w1x6z', 'visa'),
    ('Demo Customer C', 'customer-c@example.com', 'demo-c-9h2n5r3t', 'visa');

insert into customer_balances (customer_id, balance_cents)
select id, 0 from customers;
