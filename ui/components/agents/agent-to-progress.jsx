import Ago from "../Ago"
import { formatDistanceToNow } from "date-fns"
import { formatDate } from "../lib/date-time-utils"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, {
    addSuffix: false,
    includeSeconds: true
  })
}

export default function agentToProgress(agent) {
  let progress = {
    status: agent.available ? "IDLE" : "RUNNING"
  }

  if (agent.left) {
    progress.status = "LEFT"
  } else if (!agent.available) {
    progress.title = "Busy"
  }

  let changedTitle = formatDate(agent.stateChangedTime)
  progress.subtitle = (
    <Ago
      date={agent.stateChangedTime}
      formatter={formatterToNow}
      title={changedTitle}
    />
  )

  return progress
}
