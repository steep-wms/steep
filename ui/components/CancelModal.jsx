import Modal from "react-modal"
import "./CancelModal.scss"

Modal.setAppElement("#__next")

export default (props) => (
  <Modal {...props} className="cancel-modal" overlayClassName="cancel-modal-overlay">
    <div className="cancel-modal-title">{props.title}</div>
    {props.children}
    <div className="cancel-modal-buttons">
      <button className="btn" onClick={props.onDeny}>Keep it</button>
      <button className="btn btn-error" onClick={props.onConfirm}>Cancel it now</button>
    </div>
  </Modal>
)
