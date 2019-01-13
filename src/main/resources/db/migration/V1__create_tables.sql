CREATE TABLE submissions (
  id VARCHAR,
  data jsonb NOT NULL,
  PRIMARY KEY(id)
);

CREATE TABLE processchains (
  id VARCHAR,
  submissionId VARCHAR NOT NULL,
  status VARCHAR NOT NULL,
  data jsonb NOT NULL,
  results jsonb,
  errorMessage VARCHAR,
  PRIMARY KEY(id)
);

CREATE INDEX submissions_status_idx ON submissions ((data->'status'));
CREATE INDEX processchains_submissionId_idx ON processchains (submissionId);
CREATE INDEX processchains_status_idx ON processchains (status);
