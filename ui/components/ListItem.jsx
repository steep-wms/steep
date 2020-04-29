import classNames from "classnames"
import Ago from "./Ago"
import Label from "./Label"
import ListItemProgressBox from "./ListItemProgressBox"
import Tooltip from "./Tooltip"
import Link from "next/link"
import { formatDistanceToNow } from "date-fns"
import { formatDate, formatDuration, formatDurationTitle } from "./lib/date-time-utils"
import "./ListItem.scss"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: true, includeSeconds: true })
}

export default ({ title, linkHref, linkAs, subtitle, justAdded, justLeft,
    startTime, endTime, labels = [], progress }) => {
  let defaultSubtitle
  if (progress !== undefined) {
    switch (progress.status) {
      case "ACCEPTED":
      case "REGISTERED":
        defaultSubtitle = "Not started yet"
        break

      case "CANCELLING":
      case "RUNNING": {
        if (startTime) {
          let agoTitle = formatDate(startTime)
          defaultSubtitle = (
            <>Started <Ago date={startTime} formatter={formatterToNow} title={agoTitle} /></>
          )
        } else {
          defaultSubtitle = "Not started yet"
        }
        break
      }

      default:
        break
    }
  }

  if (!defaultSubtitle && startTime && endTime) {
    let agoTitle = formatDate(endTime)
    let duration = formatDuration(startTime, endTime)
    let durationTitle = formatDurationTitle(startTime, endTime)
    defaultSubtitle = (
      <>Finished <Ago date={endTime} formatter={formatterToNow} title={agoTitle} /> and
      took <Tooltip title={durationTitle}>{duration}</Tooltip></>
    )
  }

  return (
    <div className={classNames("list-item", { "just-added": justAdded && !justLeft, "just-left": justLeft })}>
      <div className="list-item-left">
        <div className="list-item-title">
          <Link href={linkHref} as={linkAs}><a>{title}</a></Link>
          {labels.map((l, i) => <Label key={i} small>{l}</Label>)}
        </div>
        <div className="list-item-subtitle">{subtitle || defaultSubtitle}</div>
      </div>
      <div className="list-item-right">
        {progress && <ListItemProgressBox progress={progress} />}
      </div>
    </div>
  )
}
