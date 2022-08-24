import classNames from "classnames"
import resolvedStyles from "./ListItemProgressBox.scss?type=resolve"
import styles from "./ListItemProgressBox.scss"
import { Activity, AlertCircle, CheckCircle, Coffee, Delete, Download, Loader,
  Moon, PauseCircle, Power, RotateCw, Trash2, XCircle } from "lucide-react"

const ListItemProgressBox = ({ progress, deleted = false }) => {
  let icon
  let defaultTitle
  switch (progress.status) {
    case "CREATING":
      defaultTitle = "Creating"
      icon = <Loader className={classNames("feather", "creating", resolvedStyles.className)} />
      break

    case "PROVISIONING":
      defaultTitle = "Provisioning"
      icon = <Download className={classNames("feather", "provisioning", resolvedStyles.className)} />
      break

    case "UP":
      defaultTitle = "Running"
      icon = <Activity className={classNames("feather", "up", resolvedStyles.className)} />
      break

    case "LEAVING":
      defaultTitle = "Leaving"
      icon = <Moon className={classNames("feather", "leaving", resolvedStyles.className)} />
      break

    case "DESTROYING":
      defaultTitle = "Destroying"
      icon = <Delete className={classNames("feather", "destroying", resolvedStyles.className)} />
      break

    case "DESTROYED":
      defaultTitle = "Destroyed"
      icon = <Delete className={classNames("feather", "destroyed", resolvedStyles.className)} />
      break

    case "ACCEPTED":
      defaultTitle = "Accepted"
      icon = <Coffee className={classNames("feather", "accepted", resolvedStyles.className)} />
      break

    case "REGISTERED":
      defaultTitle = "Registered"
      icon = <Coffee className={classNames("feather", "accepted", resolvedStyles.className)} />
      break

    case "CANCELLING":
    case "RUNNING": {
      if (progress.status === "RUNNING") {
        defaultTitle = "Running"
      } else {
        defaultTitle = "Cancelling"
      }
      icon = <RotateCw className={classNames("feather", "running", resolvedStyles.className)} />
      break
    }

    case "CANCELLED":
      defaultTitle = "Cancelled"
      icon = <Delete className={classNames("feather", "cancelled", resolvedStyles.className)} />
      break

    case "IDLE":
      defaultTitle = "Idle"
      icon = <PauseCircle className={classNames("feather", "idle", resolvedStyles.className)} />
      break

    case "LEFT":
      defaultTitle = "Left"
      icon = <Power className={classNames("feather", "left", resolvedStyles.className)} />
      break

    case "PARTIAL_SUCCESS":
      defaultTitle = "Partial success"
      icon = <AlertCircle className={classNames("feather", "partial-success", resolvedStyles.className)} />
      break

    case "SUCCESS":
      defaultTitle = "Success"
      icon = <CheckCircle className={classNames("feather", "success", resolvedStyles.className)} />
      break

    default:
      defaultTitle = "Error"
      icon = <XCircle className={classNames("feather", "error", resolvedStyles.className)} />
      break
  }

  if (deleted) {
    defaultTitle = "Deleted"
    icon = <Trash2 className={classNames("feather", resolvedStyles.className)} />
  }

  return (
    <div className="list-item-progress-box">
      {icon}
      <div>
        <strong>{progress.title || defaultTitle}</strong><br />
        {deleted || progress.subtitle}
      </div>
      {resolvedStyles.styles}
      <style jsx>{styles}</style>
    </div>
  )
}

export default ListItemProgressBox
