import "./ListItemProgressBox.scss"
import { Activity, AlertCircle, CheckCircle, Coffee, Delete, Download, Loader,
  Moon, PauseCircle, Power, RotateCw, XCircle } from "react-feather"

const ListItemProgressBox = ({ progress }) => {
  let icon
  let defaultTitle
  switch (progress.status) {
    case "CREATING":
      defaultTitle = "Creating"
      icon = <Loader className="feather creating" />
      break

    case "PROVISIONING":
      defaultTitle = "Provisioning"
      icon = <Download className="feather provisioning" />
      break

    case "UP":
      defaultTitle = "Running"
      icon = <Activity className="feather up" />
      break

    case "LEAVING":
      defaultTitle = "Leaving"
      icon = <Moon className="feather leaving" />
      break

    case "DESTROYING":
      defaultTitle = "Destroying"
      icon = <Delete className="feather destroying" />
      break

    case "DESTROYED":
      defaultTitle = "Destroyed"
      icon = <Delete className="feather destroyed" />
      break

    case "ACCEPTED":
      defaultTitle = "Accepted"
      icon = <Coffee className="feather accepted" />
      break

    case "REGISTERED":
      defaultTitle = "Registered"
      icon = <Coffee className="feather accepted" />
      break

    case "CANCELLING":
    case "RUNNING": {
      if (progress.status === "RUNNING") {
        defaultTitle = "Running"
      } else {
        defaultTitle = "Cancelling"
      }
      icon = <RotateCw className="feather running" />
      break
    }

    case "CANCELLED":
      defaultTitle = "Cancelled"
      icon = <Delete className="feather cancelled" />
      break

    case "IDLE":
      defaultTitle = "Idle"
      icon = <PauseCircle className="feather idle" />
      break

    case "LEFT":
      defaultTitle = "Left"
      icon = <Power className="feather left" />
      break

    case "PARTIAL_SUCCESS":
      defaultTitle = "Partial success"
      icon = <AlertCircle className="feather partial-success" />
      break

    case "SUCCESS":
      defaultTitle = "Success"
      icon = <CheckCircle className="feather success" />
      break

    default:
      defaultTitle = "Error"
      icon = <XCircle className="feather error" />
      break
  }

  return (
    <div className="list-item-progress-box">
      {icon}
      <div>
        <strong>{progress.title || defaultTitle}</strong><br />
        {progress.subtitle}
      </div>
    </div>
  )
}

export default ListItemProgressBox
