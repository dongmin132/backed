-- A relay run that is calling PayPal for a leg holds it until claimed_until. This is kept apart
-- from next_attempt_at (the retry backoff) so a manual run can skip the backoff without ever
-- taking a leg another run is sending right now.
alter table cashout_legs add column claimed_until timestamptz;
