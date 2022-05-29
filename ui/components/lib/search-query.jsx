const pattern = /"((\\"|[^"])*)"|'((\\'|[^'])*)'|(\S+)/g

export function addTypeExpression(query, type) {
  let parts = query.match(pattern)
  let exists = parts.some(p => p === `is:${type}` || p === `is:"${type}"` ||
      p === `is:'${type}'`)
  if (!exists) {
    query = query + ` is:${type}`
  }
  return query
}
