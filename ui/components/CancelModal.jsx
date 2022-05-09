import Modal from "./Modal"
import ModalButtons from "./ModalButtons"

const CancelModal = (props) => (
  <Modal {...props}>
    {props.children}
    <ModalButtons>
      <button className="btn" onClick={props.onDeny}>Keep it</button>
      <button className="btn btn-error" onClick={props.onConfirm}>Cancel it now</button>
    </ModalButtons>
  </Modal>
)

export default CancelModal
