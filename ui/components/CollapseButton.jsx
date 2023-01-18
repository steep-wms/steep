import styles from "./CollapseButton.scss"
import classNames from "classnames"

const CollapseButton = ({
  hangingIndent = true,
  collapsed,
  onCollapse,
  children
}) => {
  return (
    <div className="collapse-button" onClick={onCollapse}>
      <div className={classNames("icon", { "hanging-indent": hangingIndent })}>
        <svg height="1rem" width="1rem" viewBox="0 0 100 100">
          <polygon
            points="20,20 60,50 20,80"
            className="arrow"
            transform={`rotate(${collapsed ? "90" : "0"} 40 50)`}
          />
        </svg>
      </div>
      {children}
      <style jsx>{styles}</style>
    </div>
  )
}

export default CollapseButton
