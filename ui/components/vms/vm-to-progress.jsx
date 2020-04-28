import Ago from "../Ago"
import { formatDistanceToNow } from "date-fns"
import { formatDate } from "../lib/date-time-utils"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: false, includeSeconds: true })
}

export default function vmToProgress(vm) {
  let progress = {}

  switch (vm.status) {
    case "RUNNING": {
      progress.status = "UP"
      let upTitle = formatDate(vm.agentJoinTime)
      progress.subtitle = <Ago date={vm.agentJoinTime}
        formatter={formatterToNow} title={upTitle} />
      break
    }

    case "LEFT":
      progress.status = "LEAVING"
      break

    default:
      progress.status = vm.status
      break
  }

  return progress
}
