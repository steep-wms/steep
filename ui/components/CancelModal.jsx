import classNames from "classnames"
import Modal from "react-modal"
import resolvedStyles from "./CancelModal.scss?type=resolve"
import styles from "./CancelModal.scss"

Modal.setAppElement("#__next")

const CancelModal = (props) => (
  <Modal {...props} className={classNames(resolvedStyles.className, "cancel-modal")}
      overlayClassName={classNames(resolvedStyles.className, "cancel-modal-overlay")}>
    <div className="cancel-modal-title">{props.title}</div>
    {props.children}
    <div className="cancel-modal-buttons">
      <button className="btn" onClick={props.onDeny}>Keep it</button>
      <button className="btn btn-error" onClick={props.onConfirm}>Cancel it now</button>
    </div>
    {resolvedStyles.styles}
    <style jsx>{styles}</style>
  </Modal>
)

export default CancelModal
