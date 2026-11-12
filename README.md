# Backed

Backed is a prepaid-balance ledger for small shops that take customer money up front: a climbing
gym's class packs, a cafe's house tabs, any stored value a customer tops up and spends later.
PayPal is the only rail the money moves on, and every day Backed closes its books against PayPal's
own records. When PayPal shows something the ledger cannot explain, an AI investigator reads the
evidence and proposes a correction, code checks it, and a second person approves it.

## The problem

A shop that holds prepaid balances owes that money to its customers, and the owner usually keeps
track of it in a spreadsheet or a point-of-sale add-on that trusts every "payment succeeded"
message. Three things go wrong in practice:

- A payment request times out after PayPal has already charged the card. The customer sees an
  error, pays again, and is charged twice, or the shop never credits the first payment.
- Refunds are made by hand in the PayPal dashboard ("sorry about the cold coffee", "here is part of
  your balance back") and never reach the books, so the balance the shop thinks it owes drifts away
  from the money PayPal actually holds.
- When the numbers stop matching, someone has to work out what each unexplained movement was and
  write the correcting entry, which is slow and easy to get wrong.

Backed treats these as the normal case. A timeout is recorded as "outcome unknown" and settled by
asking PayPal, never by guessing. The day only counts as closed when the ledger and PayPal agree to
the cent. Anything else becomes a break that is investigated, corrected and approved.

## What happens in the demo

The demo uses three fictional members of a small climbing gym. Each has a private page
(`/c/{token}`) where they top up, cash out and message the shop. Staff sign in as `clerk`,
`maker` or `checker`.

1. **Top-up.** Maya adds $50.00 on her page. Backed creates a PayPal order paid with a sandbox
   card, PayPal captures it, and the ledger credits her $50.00 and books PayPal's fee.
2. **Lost response.** On the Faults page the maker arms "drop the response of the next capture".
   Maya adds $30.00; PayPal charges the card but Backed never hears back. Her page says
   *Processing ... please do not pay again*, and a second top-up attempt is refused while the first
   is in doubt.
3. **Resolve.** On the Top-ups page the maker resolves it. Backed re-sends the same
   `PayPal-Request-Id`, PayPal returns the capture it already made, and Maya is credited once.
4. **Spend.** The clerk charges a $12.50 day pass at the counter. The debit is conditional in the
   database, so a balance can never go below zero, even under concurrent sales.
5. **A refund outside the ledger.** On the Faults page the maker refunds $20.00 of Maya's first
   top-up directly at PayPal with the note "Returning $20.00 of your class pack balance, as you
   asked". This stands in for a refund made by hand in the PayPal dashboard. Nothing is written to
   the ledger.
6. **Close.** The maker runs the day close. Backed reads every order from PayPal and finds a refund
   it has no record of. The day is **BROKEN**: one unbooked refund, $20.00 unexplained, with
   PayPal's note shown next to it.
7. **Investigate.** The maker asks the investigator about the break. The model reads PayPal's
   refund, Maya's ledger history and her message ("Could you send $20 of my class pack back to my
   card?") and picks *balance returned*, quoting both. Code builds the journal entry from PayPal's
   amounts and runs its checks; the page shows the chosen draft and the rejected alternative side
   by side with their effect on Maya's balance and the shop's results.
8. **Approve.** The maker cannot approve their own proposal. The checker approves it, the
   correcting entry is posted dated the next business date, and the close runs again: **CLOSED**,
   nothing unexplained.
9. **Cash-out.** Maya cashes out the rest of her balance. Backed splits the refund across her
   captures, newest first, never more than is still refundable on each, and a relay sends each
   refund to PayPal outside any database transaction.
10. **Close again.** The final close is CLOSED and the business date can move on.

This exact sequence, with PayPal and the model replaced by in-memory stand-ins, is the test
`DemoStoryTest`.

## How PayPal is used

All calls go through the PayPal Java Server SDK (`com.paypal.sdk:paypal-server-sdk:2.4.0`) against
the sandbox, in `SdkPayPalGateway`.

| PayPal API | Where | Why |
|---|---|---|
| Orders v2: create order (`intent: CAPTURE`, `payment_source.card`) | top-up | Charges the customer's sandbox card in one call. `custom_id` carries the top-up id. |
| Orders v2: capture order | top-up | Only if PayPal leaves the order `APPROVED` instead of completing it on create. |
| Payments v2: refund captured payment | cash-out relay, Faults page | Pays a balance back to the card it came from, with a note to the payer. |
| Orders v2: show order details | day close, in-doubt resolution | The close compares every order's captures and refunds, including refunds made outside Backed, with the ledger. |
| Payments v2: show captured payment details | day close, investigator | Gross, fee and net of a capture, and its order. |
| Webhooks: `PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.CAPTURE.REFUNDED` | `POST /webhooks/paypal` | Each event is stored once by its id; the next close checks any capture or refund it names. |

How the calls are made:

- **Idempotency.** Every top-up and cash-out write carries a `PayPal-Request-Id` that is stored in
  the database before the call (`topup-<id>`, `cashout-<id>-leg-<n>`). A retry re-sends the same id, so PayPal returns
  the original result instead of charging or refunding twice.
- **No hidden retries.** The SDK's own retry interceptor is turned off and timeouts are explicit.
  Backed decides when to retry, based on the classification below.
- **Fees.** Gross, fee and net come from PayPal's `seller_receivable_breakdown` and
  `seller_payable_breakdown`. The ledger books what PayPal reports.
- **No Transaction Search.** The close reads orders, captures and refunds directly.
- **Webhook signatures.** Verification through `/v1/notifications/verify-webhook-signature` is
  built but off by default (`backed.webhooks.verify-signature=false`).

`SandboxCheck` (see "Run it") runs the assumptions above against the live sandbox: a card capture
completes without payer action, re-sending a request id returns the same order and capture, two
partial refunds succeed, refunds and their notes appear in the order, and an over-refund is
rejected with a 4xx. During development it passed all five checks.

## How AI is used

The investigator is one Claude API call per break (`ClaudeInvestigator`, model `claude-opus-5-5`
by default, structured JSON output, no tools). Its input is one evidence document: PayPal's refund
or capture as returned by PayPal, including the note to payer, the customer's recent ledger
history, their messages to the shop, their top-ups, their balance and any other open breaks.

| Who | Decides |
|---|---|
| The model | Which treatment fits: *balance returned*, *goodwill*, *duplicate reversal*, or *needs a human*. It must quote the evidence it relied on, name the alternative it rejected and why, and write a short note for the customer. It never supplies an amount or an id. |
| Code (`DraftBuilder`, `DraftValidator`) | The journal lines, from the treatment and PayPal's amounts. Then a checklist: the break is still open, the entry balances, the amounts equal PayPal's (recomputed independently), only the accounts allowed for that treatment are used, every quote is a verbatim substring of the evidence, the customer's balance would not go below zero, and any dollar amount in the customer note appears in the evidence. A draft that fails is stored as rejected with the reasons. |
| A person | The `maker` requests the investigation. A different user, the `checker`, approves it. The server rejects self-approval, re-validates against fresh data at approval time, and posts the entry once even if two approvals race. The close then runs again. |

Without `ANTHROPIC_API_KEY` everything else works and the proposal pages say the investigator is
not configured.

## Run it

You need Java 21. Nothing else: an embedded PostgreSQL starts with the app.

```sh
export PAYPAL_CLIENT_ID=...        # PayPal sandbox REST app
export PAYPAL_CLIENT_SECRET=...
export ANTHROPIC_API_KEY=...       # optional; only the investigator needs it
./gradlew bootRun
```

Instead of exporting them, you can copy `.env.example` to `.env` at the repository root; it is
read at startup and git-ignored.

Open <http://localhost:8080>. Demo logins, password `demo` for all:

| user | role | can |
|---|---|---|
| `clerk` | counter | charge balances at the counter |
| `maker` | operations | top-ups, cash-outs, run the close, request investigations, demo faults |
| `checker` | approver | approve or dismiss proposals |

Customer pages (no login): `/c/demo-a-7f3k9q2m`, `/c/demo-b-4p8w1x6z`, `/c/demo-c-9h2n5r3t`. The
dashboard links to them.

Top-ups use a sandbox test card. The default is the generic Visa test number; to use a card from
the PayPal sandbox card generator, set `BACKED_TEST_CARD_NUMBER`, `BACKED_TEST_CARD_EXPIRY`
(YYYY-MM) and `BACKED_TEST_CARD_CVV`.

Check the PayPal assumptions against the sandbox (charges the test card $10.00 and refunds $5.00
of it, then exits with the number of failed checks):

```sh
./gradlew bootRun --args='--spring.profiles.active=sandbox-check'
```

Run the tests. PayPal and the model are in-memory stand-ins and PostgreSQL is real; nothing calls
the network unless `ANTHROPIC_API_KEY` is exported in the shell, which also runs
`LiveInvestigatorTest` against the Claude API:

```sh
./gradlew test
```

The data lives in the embedded database and starts fresh on every run. To use your own PostgreSQL,
set `BACKED_DB_URL`, `BACKED_DB_USER` and `BACKED_DB_PASSWORD`.

## Guarantees and the tests that prove them

All tests run against a real PostgreSQL.

| Guarantee | Tests |
|---|---|
| A balance never goes below zero: 20 threads spending $1 from $10 give exactly 10 sales, and the ledger still balances | `BalanceServiceTest`, `SpendServiceTest` |
| Every journal entry balances, at most one entry per business event, and the journal is append-only in the database | `JournalServiceTest` |
| A capture whose response was lost is captured once, re-sent with the same request id, and credited once | `TopupServiceTest`, `TopupWebTest`, `FakePayPalGatewayTest` |
| A new top-up is refused while one for the same customer is in doubt | `TopupServiceTest`, `TopupWebTest` |
| A cash-out across three captures where leg 2 gets a 5xx: on resume only leg 2 is sent again, with its original request id | `CashoutTest` |
| A 4xx on a refund is never retried; the leg fails and the balance is credited back | `CashoutTest` |
| A cash-out leg is never sent twice, even with concurrent relay runs, and an in-doubt leg is never written off for lack of an answer | `CashoutTest` |
| The same webhook delivered twice, or concurrently, is processed once | `WebhookTest` |
| An off-book refund makes the close BROKEN with one break; the approved correction makes it CLOSED | `DayCloseServiceTest`, `DemoStoryTest` |
| A draft with a quote not in the evidence, an amount different from PayPal's, or a balance that would go negative is rejected | `InvestigationTest` |
| Self-approval is rejected, and two simultaneous approvals post one entry | `ApprovalTest`, `ProposalWebTest` |
| The PayPal adapter sends the request id, maps PayPal's errors as in the table below, and makes exactly one request per call | `SdkPayPalGatewayTest` (against a local stub, offline) |
| The whole demo story works through the web routes | `DemoStoryTest` |

## Error classification

Every PayPal call ends in one of four outcomes.

| PayPal answer | Meaning | Top-up | Cash-out leg | Day close |
|---|---|---|---|---|
| 2xx | done | `CAPTURED` and booked | `DONE` and booked | uses the data |
| 4xx business error (for example `INSTRUMENT_DECLINED`, `REFUND_AMOUNT_EXCEEDED`) | will never succeed | `DECLINED`, nothing booked | `FAILED` at once, balance credited back | (404 on a read: the object does not exist) |
| 5xx, 408, 409, 429, connection refused, DNS or TLS failure | not done | first attempt: `DECLINED`, nothing charged, customer may try again; while resolving: stays `IN_DOUBT` | stays `PENDING` and is retried later with the same request id; after 5 attempts `FAILED` and credited back. A leg already `IN_DOUBT` stays `IN_DOUBT` | the run is recorded as BROKEN with the reason; no breaks are written |
| read timeout, or connection dropped during a write | outcome unknown | `IN_DOUBT`, resolved by re-sending the same request id | `IN_DOUBT`, re-sent with the same request id | (on reads, treated as not done) |

## Limits

- One shop, one currency (USD), three demo staff users held in memory.
- Card handling is demo-only: the server sends a sandbox test card's number to PayPal. A real
  deployment would collect cards with PayPal's hosted card fields or use vaulted payment tokens so
  card data never touches the server.
- This is not a regulated stored-value product, and it makes no claim of legal, regulatory or
  PCI compliance.
- The investigator has been exercised with a scripted stand-in. `LiveInvestigatorTest` calls the
  real model on three situations, but only runs when `ANTHROPIC_API_KEY` is set.
- Webhook signature verification is off by default and has not been run against PayPal.
- Close runs are not serialised against each other, and the close reads PayPal orders one at a
  time, which is fine at demo scale.

## Tools used

- The Claude API, through the Anthropic Java SDK, for the investigator.
- The PayPal Java Server SDK, generated by APIMatic, for Orders and Payments.
- Spring Boot 4, Thymeleaf, PostgreSQL (embedded through `io.zonky.test:embedded-postgres`), Flyway.
- The code was written with an AI coding agent.

## License

MIT. See [LICENSE](LICENSE).
