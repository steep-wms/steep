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

function getLocatorAliases(locator) {
  if (locator === "rcs") {
    return ["rc", "cap", "reqcap", "capability", "requiredcapability",
    "rcs", "caps", "reqcaps", "capabilities", "requiredcapabilities"]
  } else if (locator === "error") {
    return ["error", "errormessage"]
  } else if (locator === "start") {
    return ["start", "starttime"]
  } else if (locator === "end") {
    return ["end", "endtime"]
  }
  return [locator]
}

function isLocator(part, locator) {
  let aliases = getLocatorAliases(locator)
  let lp = part.toLowerCase()
  for (let a of aliases) {
    let lt = a.toLowerCase()
    if (lp === `in:${lt}` || lp === `in:"${lt}"` || lp === `in:'${lt}'`) {
      return true
    }
  }
  return false
}

export function hasLocator(query, locator) {
  let parts = query.match(pattern)
  return parts.some(p => isLocator(p, locator))
}

export function toggleLocator(query, locator) {
  if (!hasLocator(query, locator)) {
    query = query + ` in:${locator}`
  } else {
    let parts = query.match(pattern)
    parts = parts.filter(p => !isLocator(p, locator))
    query = parts.join(" ")
  }
  return query
}
