-- NULL was accidentally stored as "null"
UPDATE processchains SET starttime = NULL WHERE starttime = 'null';
UPDATE processchains SET endtime = NULL WHERE endtime = 'null';

-- timestamps were accidentally stored as quoted strings
UPDATE processchains SET starttime = TRIM(BOTH '"' FROM starttime);
UPDATE processchains SET endtime = TRIM(BOTH '"' FROM endtime);
