-- For each submission, get all distinct values of the requiredCapabilities JSON
-- array from all its process chains and then add them as a JSON array to data.
-- Coalesce to an empty array if the process chains do not have required capabilities.
UPDATE submissions SET data = data || jsonb_build_object('requiredCapabilities', (
  WITH c AS (
    SELECT DISTINCT jsonb_array_elements(COALESCE(data->'requiredCapabilities', '[]'::jsonb)) AS pccaps
      FROM processchains
      WHERE submissionId=submissions.id
  )
  SELECT COALESCE(jsonb_agg(pccaps), '[]'::jsonb) AS caps FROM c
));
