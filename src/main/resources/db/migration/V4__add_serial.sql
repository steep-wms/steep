ALTER TABLE submissions ADD COLUMN serial SERIAL NOT NULL;
ALTER TABLE processchains ADD COLUMN serial SERIAL NOT NULL;

CREATE INDEX submissions_serial_idx ON submissions (serial);
CREATE INDEX processchains_serial_idx ON processchains (serial);
