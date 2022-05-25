import classNames from "classnames"
import styles from "./Alert.scss"

const Alert = ({ children, error, warning, info, small }) => (
  <div className={classNames("alert", { error, warning, info, small })}>
    {children}
    <style jsx>{styles}</style>
  </div>
)

export default Alert
