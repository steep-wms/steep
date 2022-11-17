-- add new column
ALTER TABLE processchains ADD COLUMN runs jsonb;

-- convert old columns to run
UPDATE processchains SET runs = jsonb_build_array(jsonb_build_object(
  'startTime', startTime,
  'endTime', endTime,
  'status', status,
  'errorMessage', errorMessage
)) WHERE status != 'REGISTERED' AND startTime IS NOT NULL;

-- drop old columns
ALTER TABLE processchains DROP COLUMN errorMessage;
ALTER TABLE processchains DROP COLUMN startTime;
ALTER TABLE processchains DROP COLUMN endTime;
