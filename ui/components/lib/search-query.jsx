const pattern = /"((\\"|[^"])*)"|'((\\'|[^'])*)'|(\S+)/g

export function addTypeExpression(query, type) {
  if (!hasTypeExpression(query, type)) {
    query = query + ` is:${type}`
  }
  return query
}

export function removeAllTypeExpressions(query) {
  let parts = query.match(pattern)
  parts = parts.filter(p => !(p.toLowerCase().startsWith("is:") && p.length > 3))
  return parts.join(" ")
}

export function hasTypeExpression(query, type) {
  let lt = type.toLowerCase()
  let parts = query.match(pattern)
  return parts.some(p => p.toLowerCase() === `is:${lt}` ||
      p.toLowerCase() === `is:"${lt}"` ||
      p.toLowerCase() === `is:'${lt}'`)
}

export function hasAnyTypeExpression(query) {
  let parts = query.match(pattern)
  return parts.some(p => p.toLowerCase().startsWith("is:") && p.length > 3)
}
