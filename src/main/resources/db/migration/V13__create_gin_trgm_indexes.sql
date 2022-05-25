CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION rcs_to_string(v jsonb)
  RETURNS text
  LANGUAGE sql
  IMMUTABLE
  PARALLEL SAFE
  STRICT
AS $$
  SELECT array_to_string(ARRAY(SELECT jsonb_array_elements_text(v)), E'\u00a0')
$$;

CREATE INDEX IF NOT EXISTS submissions_id_gin_idx ON submissions USING gin(id gin_trgm_ops);
CREATE INDEX IF NOT EXISTS submissions_errormessage_gin_idx ON submissions USING gin(errorMessage gin_trgm_ops);
CREATE INDEX IF NOT EXISTS submissions_name_gin_idx ON submissions USING gin((data->>'name') gin_trgm_ops);
CREATE INDEX IF NOT EXISTS submissions_source_gin_idx ON submissions USING gin((data->>'source') gin_trgm_ops);
CREATE INDEX IF NOT EXISTS submissions_requiredCapabilities_gin_idx ON submissions USING gin(rcs_to_string(data->'requiredCapabilities') gin_trgm_ops);

CREATE INDEX IF NOT EXISTS processchains_id_gin_idx ON processchains USING gin(id gin_trgm_ops);
CREATE INDEX IF NOT EXISTS processchains_errormessage_gin_idx ON processchains USING gin(errorMessage gin_trgm_ops);
CREATE INDEX IF NOT EXISTS processchains_requiredCapabilities_gin_idx ON processchains USING gin(rcs_to_string(data->'requiredCapabilities') gin_trgm_ops);
