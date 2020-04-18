CREATE TABLE vms (
  id VARCHAR,
  data jsonb NOT NULL,
  serial SERIAL NOT NULL,
  PRIMARY KEY(id)
);

CREATE INDEX vms_externalId_idx ON vms ((data->'externalId'));
CREATE INDEX vms_status_idx ON vms ((data->'status'));
CREATE INDEX vms_setupId_idx ON vms ((data->'setup'->'id'));
CREATE INDEX vms_serial_idx ON vms (serial);
