import styles from "./Notification.scss"

const Notification = ({ children }) => (
  <div className="notification">
    {children}
    <style jsx>{styles}</style>
  </div>
)

export default Notification
