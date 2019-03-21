CREATE TABLE persistentmap (
  name VARCHAR,
  k VARCHAR,
  v VARCHAR,
  CONSTRAINT persistentmap_name_k PRIMARY KEY(name, k)
);
