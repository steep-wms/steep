import classNames from "classnames"
import Ago from "./Ago"
import Label from "./Label"
import ListItemProgressBox from "./ListItemProgressBox"
import Tooltip from "./Tooltip"
import Link from "next/link"
import { formatDistanceToNow } from "date-fns"
import { formatDate, formatDuration, formatDurationTitle } from "./lib/date-time-utils"
import styles from "./ListItem.scss"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: true, includeSeconds: true })
}

const ListItem = ({ title, linkHref, linkAs, subtitle, deleted = false, justAdded, justLeft,
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

  let titleLink
  if (!deleted) {
    titleLink = <Link href={linkHref} as={linkAs}><a className="list-item-title-link">{title}<style jsx>{styles}</style></a></Link>
  } else {
    titleLink = <span className="list-item-title-link">{title}<style jsx>{styles}</style></span>
  }

  return (
    <div className={classNames("list-item", { "just-added": justAdded && !justLeft, "just-left": justLeft, deleted })}>
      <div className="list-item-left">
        <div className="list-item-title">
          {titleLink}{labels.length > 0 && <>&ensp;</>}
          {labels.map((l, i) => <Label key={i} small>{l}</Label>)}
        </div>
        <div className="list-item-subtitle">{subtitle || defaultSubtitle}</div>
      </div>
      <div className="list-item-right">
        {progress && <ListItemProgressBox progress={progress} deleted={deleted} />}
      </div>
      <style jsx>{styles}</style>
    </div>
  )
}

export default ListItem
