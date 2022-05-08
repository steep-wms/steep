function formatPriority(priority) {
  if (priority === undefined || priority === null) {
    priority = 0
  }
  switch (priority) {
    case 0:
      return "0 (normal)"

    case 10:
      return "10 (high)"

    case 100:
      return "100 (higher)"

    case 1000:
      return "1000 (very high)"

    case -10:
      return "-10 (low)"

    case -100:
      return "-100 (lower)"

    case -1000:
      return "-1000 (very low)"

    default:
      return `${priority}`
  }
}

const Priority = ({ value }) => {
  return <>{formatPriority(value)}</>
}

export default Priority
