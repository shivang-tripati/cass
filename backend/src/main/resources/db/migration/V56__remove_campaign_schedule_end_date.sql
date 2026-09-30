-- VB-8J: remove the campaign schedule end date.
--
-- A campaign has a start date and then remains eligible across every future
-- calling window until its work is exhausted. There is no schedule expiry, so
-- the column that expressed one is removed rather than left behind unused.
--
-- The daily window (daily_start_time / daily_end_time) is unaffected: closing
-- it defers unfinished work to the next valid window rather than ending the
-- schedule.

-- ck_campaigns_schedule_dates exists only to order the two dates against each
-- other, so it goes with the column.
ALTER TABLE campaigns DROP CONSTRAINT IF EXISTS ck_campaigns_schedule_dates;

ALTER TABLE campaigns DROP COLUMN IF EXISTS schedule_end_date;

-- Execution snapshots freeze the same configuration, so the frozen copy goes
-- too. Snapshots already written keep their rows; only the column is dropped.
ALTER TABLE campaign_execution_configurations
    DROP COLUMN IF EXISTS schedule_end_date;