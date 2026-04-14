-- Supports sub-case wiring: a child CaseInstance references the parent PlanItem<SubCase>.
-- Null for root cases (the vast majority). No foreign key — PlanItems are in-memory.
ALTER TABLE case_instance ADD COLUMN IF NOT EXISTS parent_plan_item_id UUID;
