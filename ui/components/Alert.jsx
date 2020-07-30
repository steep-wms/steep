import classNames from "classnames"
import "./Alert.scss"

const Alert = ({ children, error, warning, info }) => (
  <div className={classNames("alert", { error, warning, info })}>{children}</div>
)

export default Alert
