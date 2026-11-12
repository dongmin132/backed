-- Investigate module (version range V50 to V59): what the model saw and how the draft was judged,
-- kept with each proposal so an approval can be audited later.
alter table proposals
    add column evidence_bundle   text,
    add column alternative_draft text,
    add column validation_checks text,
    -- who approved or dismissed; approved_by stays reserved for the approving checker
    add column decided_by        text;
