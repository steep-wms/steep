import classNames from "classnames"
import "./Label.scss"

const Label = ({ children, small }) => (
  <span className={classNames("label", { "label-small": small })}>{children}</span>
)

export default Label
