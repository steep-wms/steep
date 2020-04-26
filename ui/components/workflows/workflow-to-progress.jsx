import Link from "next/link"

export default function workflowToProgress(workflow) {
  let progressTitle
  let progressSubTitle
  if (workflow.status === "RUNNING") {
    let completed = workflow.succeededProcessChains +
       workflow.failedProcessChains + workflow.cancelledProcessChains
    progressTitle = `${workflow.runningProcessChains} Running`
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

  if (typeof progressSubTitle !== "undefined") {
    progressSubTitle = (
      <Link href={{
        pathname: "/processchains",
        query: {
          submissionId: workflow.id
        }
      }}>
        <a>{progressSubTitle}</a>
      </Link>
    )
  }

  let status = workflow.status
  if (status === "RUNNING" && workflow.cancelledProcessChains > 0) {
    status = "CANCELLING"
  }

  return {
    status,
    title: progressTitle,
    subtitle: progressSubTitle
  }
}
