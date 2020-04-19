import classNames from "classnames"
import "./ListItem.scss"
import { CheckCircle } from "react-feather"
import Link from "next/link"

export default ({ title, href, subtitle, justAdded }) => (
  <div className={classNames("list-item", { "just-added": justAdded })}>
    <div className="list-item-left">
      <h4><Link href={href}><a>{title}</a></Link></h4>
      {subtitle}
    </div>
    <div className="list-item-right">
      <div className="list-item-progress-box">
        <CheckCircle className="feather" />
        <div>
          <strong>Finished</strong><br />
          2 completed
        </div>
      </div>
    </div>
  </div>
)
