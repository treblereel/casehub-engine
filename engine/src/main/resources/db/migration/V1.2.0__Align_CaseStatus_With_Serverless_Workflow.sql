-- Align CaseInstance state values and EventLog event_type values with the
-- CNCF Serverless Workflow specification (as used by Quarkus Flow).
--
-- Renames:
--   ACTIVE      → RUNNING   (case is now directly RUNNING on creation; no PENDING state)
--   FAILED      → FAULTED   (aligns with WorkflowStatus.FAULTED)
--   TERMINATED  → CANCELLED (aligns with WorkflowStatus.CANCELLED; was unused in practice)
--
-- WAITING, SUSPENDED, COMPLETED are unchanged.

UPDATE case_instance SET state = 'RUNNING'    WHERE state = 'ACTIVE';
UPDATE case_instance SET state = 'FAULTED'    WHERE state = 'FAILED';
UPDATE case_instance SET state = 'CANCELLED'  WHERE state = 'TERMINATED';

-- Migrate matching event_type values in the event log
UPDATE event_log SET event_type = 'CASE_FAULTED' WHERE event_type = 'CASE_FAILED';
