import classNames from "classnames"
import "./ListItem.scss"
import { AlertCircle, CheckCircle, Coffee, Delete, RotateCw, XCircle } from "react-feather"
import Link from "next/link"
import Label from "./Label"

export default ({ title, linkHref, linkAs, subtitle, justAdded, labels = [], progress }) => {
  let progressBox
  if (typeof progress !== "undefined") {
    let icon
    let defaultTitle
    switch (progress.status) {
      case "ACCEPTED":
        defaultTitle = "Accepted"
        icon = <Coffee className="feather accepted" />
        break

      case "REGISTERED":
        defaultTitle = "Registered"
        icon = <Coffee className="feather accepted" />
        break

      case "RUNNING":
        defaultTitle = "Running"
        icon = <RotateCw className="feather running" />
        break

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

  return (
    <div className={classNames("list-item", { "just-added": justAdded })}>
      <div className="list-item-left">
        <div className="list-item-title">
          <Link href={linkHref} as={linkAs}><a>{title}</a></Link>
          {labels.map((l, i) => <Label key={i} small>{l}</Label>)}
        </div>
        <div className="list-item-subtitle">{subtitle}</div>
      </div>
      <div className="list-item-right">
        {progressBox}
      </div>
    </div>
  )
}
