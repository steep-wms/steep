import classNames from "classnames"
import "./ListItem.scss"
import { AlertCircle, CheckCircle, Coffee, Delete, RotateCw, XCircle } from "react-feather"
import Link from "next/link"
import Label from "./Label"
import { formatDistanceToNow } from "date-fns"
import TimeAgo from "react-timeago"

import dayjs from "dayjs"
import Duration from "dayjs/plugin/duration"
import RelativeTime from "dayjs/plugin/relativeTime"
dayjs.extend(Duration)
dayjs.extend(RelativeTime)

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: true, includeSeconds: true })
}

function formatDuration(startTime, endTime) {
  let diff = dayjs(endTime).diff(dayjs(startTime))
  let duration = Math.ceil(dayjs.duration(diff).asSeconds())
  let seconds = Math.floor(duration % 60)
  let minutes = Math.floor(duration / 60 % 60)
  let hours = Math.floor(duration / 60 / 60)
  let result = ""
  if (hours > 0) {
    result += hours + "h "
  }
  if (result !== "" || minutes > 0) {
    result += minutes + "m "
  }
  result += seconds + "s"
  return result
}

export default ({ title, linkHref, linkAs, subtitle, justAdded,
    startTime, endTime, labels = [], progress }) => {
  let defaultSubtitle
  let progressBox
  if (typeof progress !== "undefined") {
    let icon
    let defaultTitle
    switch (progress.status) {
      case "ACCEPTED":
        defaultTitle = "Accepted"
        defaultSubtitle = "Not started yet"
        icon = <Coffee className="feather accepted" />
        break

      case "REGISTERED":
        defaultTitle = "Registered"
        defaultSubtitle = "Not started yet"
        icon = <Coffee className="feather accepted" />
        break

      case "CANCELLING":
      case "RUNNING": {
        if (progress.status === "RUNNING") {
          defaultTitle = "Running"
        } else {
          defaultTitle = "Cancelling"
        }
        if (startTime) {
          let agoTitle = dayjs(startTime).format("dddd, D MMMM YYYY, h:mm:ss a")
          defaultSubtitle = (
            <>Started <TimeAgo date={startTime} formatter={formatterToNow} title={agoTitle} /></>
          )
        }
        icon = <RotateCw className="feather running" />
        break
      }

      case "CANCELLED":
        defaultTitle = "Cancelled"
        icon = <Delete className="feather cancelled" />
        break

      case "PARTIAL_SUCCESS":
        defaultTitle = "Partial success"
        icon = <AlertCircle className="feather partial-success" />
        break

      case "SUCCESS":
        defaultTitle = "Success"
        icon = <CheckCircle className="feather success" />
        break

      default:
        defaultTitle = "Error"
        icon = <XCircle className="feather error" />
        break
    }

    progressBox = (
      <div className="list-item-progress-box">
        {icon}
        <div>
          <strong>{progress.title || defaultTitle}</strong><br />
          {progress.subtitle}
        </div>
      </div>
    )
  }

  if (!defaultSubtitle && startTime && endTime) {
    let agoTitle = dayjs(endTime).format("dddd, D MMMM YYYY, h:mm:ss a")
    let diff = dayjs(endTime).diff(dayjs(startTime))
    let duration = dayjs.duration(diff).humanize()
    let durationTitle = formatDuration(startTime, endTime)
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
        {progressBox}
      </div>
    </div>
  )
}
