import Modal from "./Modal"
import ModalButtons from "./ModalButtons"
import styles from "./Priority.scss"
import { useEffect, useRef, useState } from "react"
import { Edit2 } from "lucide-react"
import classNames from "classnames"

function formatPriority(priority) {
  if (priority === undefined || priority === null) {
    priority = 0
  }
  switch (priority) {
    case 0:
      return "0 (normal)"

    case 10:
      return "10 (high)"

    case 100:
      return "100 (higher)"

    case 1000:
      return "1000 (very high)"

    case -10:
      return "-10 (low)"

    case -100:
      return "-100 (lower)"

    case -1000:
      return "-1000 (very low)"

    default:
      return `${priority}`
  }
}

const Priority = ({ value = 0, editable, onChange, subjectShort, subjectLong }) => {
  const [confirmModalOpen, setConfirmModalOpen] = useState()
  const [editing, setEditing] = useState()
  const [inputValue, setInputValue] = useState(value)
  const inputRef = useRef()

  useEffect(() => {
    setInputValue(value)
  }, [value])

  function startEditing() {
    setEditing(true)
    setTimeout(() => {
      inputRef.current.focus()
      inputRef.current.select()
    }, 0)
  }

  function stopEditing(newValue = inputValue) {
    if (newValue === "") {
      setInputValue(value)
      setEditing(false)
    } else {
      let i = +newValue
      if (i < 2000000000 && i > -2000000000 && i !== value) {
        setConfirmModalOpen(true)
      } else {
        setInputValue(value)
        setEditing(false)
      }
    }
  }

  function onInputKeyDown(e) {
    if (e.keyCode === 27) {
      setInputValue(value)
      stopEditing(value)
    } else if (e.keyCode === 13) {
      stopEditing()
    }
  }

  function onConfirm() {
    if (onChange) {
      onChange(+inputValue)
    }
    setInputValue(value) // reset input value in case onChange doesn't do anything
    setConfirmModalOpen(false)
    setEditing(false)
  }

  function onDeny() {
    setInputValue(value)
    setConfirmModalOpen(false)
    setEditing(false)
  }

  if (editable) {
    return <span className={classNames({ editing })}>
      <span className="priority-label" onClick={startEditing}>
        <span className="number">{formatPriority(value)}</span> <span className="icon"><Edit2 size="1em" /></span>
      </span>

      <input className="priority-input small" ref={inputRef}
        onBlur={() => stopEditing()} type="number" value={inputValue}
        onChange={e => setInputValue(e.target.value)} onKeyDown={onInputKeyDown} />

      <Modal isOpen={confirmModalOpen} contentLabel="Confirm modal"
          onRequestClose={onDeny} title={`Change ${subjectShort} priority`}
          onConfirm={onConfirm} onDeny={onDeny}>
        <p>Are you sure you want to change the priority of this {subjectLong} to <strong>{+inputValue}</strong>?</p>
        <ModalButtons>
          <button className="btn" onClick={onDeny}>Cancel</button>
          <button className="btn btn-primary" onClick={onConfirm}>Change priority</button>
        </ModalButtons>
      </Modal>

      <style jsx>{styles}</style>
    </span>
  } else {
    return <>{formatPriority(value)}</>
  }
}

export default Priority
