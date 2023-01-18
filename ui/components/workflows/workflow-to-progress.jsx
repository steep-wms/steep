import Link from "next/link"

export default function workflowToProgress(workflow) {
  let progressTitle
  let progressSubTitle
  if (workflow.status === "RUNNING") {
    let completed =
      workflow.succeededProcessChains +
      workflow.failedProcessChains +
      workflow.cancelledProcessChains
    progressTitle = `${
      workflow.runningProcessChains + workflow.pausedProcessChains
    } Running`
    progressSubTitle = `${completed} of ${workflow.totalProcessChains} completed`
  } else if (workflow.status !== "ACCEPTED" && workflow.status !== "RUNNING") {
    if (workflow.failedProcessChains > 0) {
      if (workflow.failedProcessChains !== workflow.totalProcessChains) {
        progressSubTitle = `${workflow.failedProcessChains} of ${workflow.totalProcessChains} failed`
      } else {
        progressSubTitle = `${workflow.failedProcessChains} failed`
      }
    } else {
      progressSubTitle = `${workflow.totalProcessChains} completed`
    }
  }

  if (progressSubTitle !== undefined) {
    progressSubTitle = (
      <Link
        href={{
          pathname: "/processchains",
          query: {
            submissionId: workflow.id
          }
        }}
      >
        {progressSubTitle}
      </Link>
    )
  }

  let status = workflow.status
  if (status === "RUNNING" && workflow.cancelledProcessChains > 0) {
    status = "CANCELLING"
    progressTitle = undefined
  }

  return {
    status,
    title: progressTitle,
    subtitle: progressSubTitle
  }
}
