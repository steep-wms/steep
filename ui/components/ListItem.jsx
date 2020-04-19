import classNames from "classnames"
import "./ListItem.scss"
import { CheckCircle } from "react-feather"
import Link from "next/link"
import Label from "./Label"

export default ({ title, linkHref, linkAs, subtitle, justAdded, labels = [], progress }) => {
  let progressBox
  if (typeof progress !== "undefined") {
    progressBox = (
      <div className="list-item-progress-box">
        <CheckCircle className="feather" />
        <div>
          <strong>{progress.title || "Finished"}</strong><br />
          2 completed
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
