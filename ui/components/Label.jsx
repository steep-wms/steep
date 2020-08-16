import classNames from "classnames"
import styles from "./Label.scss"

const Label = ({ children, small }) => (
  <span className={classNames("label", { "label-small": small })}>
    {children}
    <style jsx>{styles}</style>
  </span>
)

export default Label
