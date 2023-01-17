import Link from "next/link"
import dayjs from "dayjs"
import { Check, RotateCw, Delete, CheckCircle, XCircle, PauseCircle } from "lucide-react"
import classNames from "classnames"
import styles from "./RunMenuItem.scss"

function formatDateSince(date) {
  return dayjs(date).format("[since] D MMM YYYY, h:mm:ss a")
}

function formatDateOnAt(date) {
  return dayjs(date).format("[on] D MMM YYYY [at] h:mm:ss a")
}

const RunMenuItem = ({ processChainId, runNumber, isLatest, run, enabled = false }) => {
  let text
  let cls
  let statusIcon

  switch (run.status) {
    case "ERROR":
      statusIcon = <XCircle />
      text = `Failed ${formatDateOnAt(run.endTime)}`
      cls = "error"
      break

    case "CANCELLED":
      statusIcon = <Delete />
      text = `Cancelled ${formatDateOnAt(run.endTime)}`
      cls = "cancelled"
      break

    case "SUCCESS":
      statusIcon = <CheckCircle />
      text = `Succeeded ${formatDateOnAt(run.endTime)}`
      cls = "success"
      break

    case "PAUSED":
      statusIcon = <PauseCircle />
      text = `Scheduled ${formatDateOnAt(run.autoResumeAfter)}`
      cls = "paused"
      break

    default:
      statusIcon = <RotateCw />
      text = `Running since ${formatDateSince(run.startTime)}`
      cls = "running"
      break
  }

  let url
  if (!isLatest && runNumber !== undefined) {
    url = `/processchains/${processChainId}/runs/${runNumber}`
  } else {
    url = `/processchains/${processChainId}`
  }

  return (
    <>
      <div className="run-menu-item">
        <Link href={url}>
          <div className="run-menu-item-item">
            <li>
              <div className="run-menu-item-content">
                { enabled ? <Check /> : undefined }
                <span className="title">
                  {run.status === "PAUSED" && run.autoResumeAfter !== undefined ? (
                    <>Next run</>
                  ) : runNumber === undefined ? (
                    <>Process chain</>
                  ) : (
                    <>Run #{runNumber}</>
                  )}
                </span>
                <div className={classNames("status", cls)}>{statusIcon} {text}</div>
              </div>
            </li>
          </div>
        </Link>
      </div>
      <style jsx>{styles}</style>
    </>
  )
}

export default RunMenuItem
