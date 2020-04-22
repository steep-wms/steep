import classNames from "classnames"
import Label from "./Label"
import ListItemProgressBox from "./ListItemProgressBox"
import Link from "next/link"
import { formatDistanceToNow } from "date-fns"
import TimeAgo from "react-timeago"
import { formatDate, formatDuration, formatDurationTitle } from "./lib/date-time-utils"
import "./ListItem.scss"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: true, includeSeconds: true })
}

export default ({ title, linkHref, linkAs, subtitle, justAdded,
    startTime, endTime, labels = [], progress }) => {
  let defaultSubtitle
  if (typeof progress !== "undefined") {
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
            <>Started <TimeAgo date={startTime} formatter={formatterToNow} title={agoTitle} /></>
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
      <>Finished <TimeAgo date={endTime} formatter={formatterToNow} title={agoTitle} /> and
      took <span title={durationTitle}>{duration}</span></>
    )
  }

  return (
    <div className={classNames("list-item", { "just-added": justAdded })}>
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
