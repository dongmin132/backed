-- Investigate module (version range V50 to V59): which provider and model made each proposal,
-- so the page and the audit trail show who answered. Null on proposals made before this column.
alter table proposals
    add column ai_provider text,
    add column ai_model    text;
