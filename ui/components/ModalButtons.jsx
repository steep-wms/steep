import styles from "./ModalButtons.scss"

const ModalButtons = props => (
  <div className="modal-buttons">
    {props.children}
    <style jsx>{styles}</style>
  </div>
)

export default ModalButtons
