import classNames from "classnames"
import "./Alert.scss"

export default ({ children, error, warning, info }) => (
  <div className={classNames("alert", { error, warning, info })}>{children}</div>
)
