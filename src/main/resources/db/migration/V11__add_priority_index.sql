CREATE INDEX submissions_priority_idx ON submissions ((data->'workflow'->'priority'));
CREATE INDEX processchains_priority_idx ON processchains ((data->'priority'));
