import classNames from "classnames"
import "./Label.scss"

export default ({ children, small }) => (
  <span className={classNames("label", { "label-small": small })}>{children}</span>
)
