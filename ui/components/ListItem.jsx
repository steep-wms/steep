import classNames from "classnames"
import "./ListItem.scss"
import { CheckCircle } from "react-feather"

export default ({ title, subtitle, justAdded }) => (
  <div className={classNames("list-item", { "just-added": justAdded })}>
    <div className="list-item-left">
      <h4><a href="#">{title}</a></h4>
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
