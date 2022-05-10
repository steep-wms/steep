import resolvedStyles from "./Modal.scss?type=resolve"
import styles from "./Modal.scss"
import { useRef } from "react"
import ReactModal from "react-modal"
import { disableBodyScroll, clearAllBodyScrollLocks } from "body-scroll-lock"
import classNames from "classnames"

ReactModal.setAppElement("#__next")

const Modal = (props) => {
  const ref = useRef()

  function onModalOpen() {
    disableBodyScroll(ref.current)
  }

  function onModalClose() {
    // we must use clearAllBodyScrollLocks here instead of enableBodyScroll
    // because the component might already be disposed at this point
    clearAllBodyScrollLocks()
  }

  return (
    <ReactModal {...props} className={classNames(resolvedStyles.className, "modal")}
        overlayClassName={classNames(resolvedStyles.className, "modal-overlay")}
        onAfterOpen={onModalOpen} onAfterClose={onModalClose} ref={ref}>
      <div className="modal-title">{props.title}</div>
      {props.children}
      {resolvedStyles.styles}
      <style jsx>{styles}</style>
    </ReactModal>
  )
}

export default Modal
