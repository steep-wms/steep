CREATE TABLE submissions (
  id VARCHAR(64),
  data jsonb NOT NULL,
  PRIMARY KEY(id)
);

CREATE TABLE processchains (
  id VARCHAR(64),
  submissionId VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  data jsonb NOT NULL,
  results jsonb,
  PRIMARY KEY(id)
);

CREATE INDEX submissions_status_idx ON submissions ((data->'status'));
CREATE INDEX processchains_submissionId_idx ON processchains (submissionId);
CREATE INDEX processchains_status_idx ON processchains (status);
