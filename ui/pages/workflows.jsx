import EventBusContext from "../components/lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import Page from "../components/layouts/Page"
import Alert from "../components/Alert"
import ListItem from "../components/ListItem"
import { useContext, useEffect, useReducer, useRef } from "react"
import useSWR from "swr"
import fetcher from "../components/lib/json-fetcher"
import listItemUpdateReducer from "../components/lib/listitem-update-reducer"

import {
  SUBMISSION_ADDED,
  SUBMISSION_START_TIME_CHANGED,
  SUBMISSION_END_TIME_CHANGED,
  SUBMISSION_STATUS_CHANGED,
  SUBMISSION_ERROR_MESSAGE_CHANGED,
  PROCESS_CHAINS_ADDED_SIZE,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED
} from "../components/lib/EventBusMessages"

function initWorkflow(w) {
  delete w.workflow
  w.runningProcessChains = w.runningProcessChains || 0
  w.succeededProcessChains = w.succeededProcessChains || 0
  w.cancelledProcessChains = w.cancelledProcessChains || 0
  w.failedProcessChains = w.failedProcessChains || 0
  w.totalProcessChains = w.totalProcessChains || 0
  w.startTime = w.startTime || null
  w.endTime = w.endTime || null
}

function workflowToElement(workflow) {
  let href = `/workflows/${workflow.id}`

  let status = workflow.status
  if (status === "RUNNING" && workflow.cancelledProcessChains > 0) {
    status = "CANCELLING"
  }

  let progressTitle
  let progressSubTitle
  if (workflow.status === "RUNNING") {
    let completed = workflow.succeededProcessChains +
       workflow.failedProcessChains + workflow.cancelledProcessChains
    progressTitle = `${workflow.runningProcessChains} Running`
    progressSubTitle = (
      <a href="#">
        {completed} of {workflow.totalProcessChains} completed
      </a>
    )
  } else if (workflow.status !== "ACCEPTED" && workflow.status !== "RUNNING") {
    let text
    if (workflow.failedProcessChains > 0) {
      if (workflow.failedProcessChains !== workflow.totalProcessChains) {
        text = `${workflow.failedProcessChains} of ${workflow.totalProcessChains} failed`
      } else {
        text = `${workflow.failedProcessChains} failed`
      }
    } else {
      text = `${workflow.totalProcessChains} completed`
    }
    progressSubTitle = <a href="#">{text}</a>
  }

  let progress = {
    status,
    title: progressTitle,
    subtitle: progressSubTitle
  }

  return (
    <ListItem key={workflow.id} justAdded={workflow.justAdded}
      linkHref={href} title={workflow.id} startTime={workflow.startTime}
      endTime={workflow.endTime} progress={progress} />
  )
}

function updateWorkflowsReducer(pageSize) {
  let liur = listItemUpdateReducer(pageSize, (workflow) => {
    initWorkflow(workflow)
    workflow.element = workflowToElement(workflow)
  })

  return (state, { action = "unshift", workflows }) => {
    switch (action) {
      case "update":
      case "unshift":
      case "push":
        return liur(state, { action, items: workflows })

      case "updateAddProcessChains": {
        for (let workflow of workflows) {
          let i = state.findIndex(w => w.id === workflow.id)
          if (i >= 0) {
            let newWorkflow = { ...state[i] }
            if (typeof workflow.totalProcessChains !== "undefined") {
              newWorkflow.totalProcessChains =
                  Math.max(0, newWorkflow.totalProcessChains + workflow.totalProcessChains)
            }
            if (typeof workflow.runningProcessChains !== "undefined") {
              newWorkflow.runningProcessChains =
                  Math.max(0, newWorkflow.runningProcessChains + workflow.runningProcessChains)
            }
            if (typeof workflow.cancelledProcessChains !== "undefined") {
              newWorkflow.cancelledProcessChains =
                  Math.max(0, newWorkflow.cancelledProcessChains + workflow.cancelledProcessChains)
            }
            if (typeof workflow.failedProcessChains !== "undefined") {
              newWorkflow.failedProcessChains =
                  Math.max(0, newWorkflow.failedProcessChains + workflow.failedProcessChains)
            }
            if (typeof workflow.succeededProcessChains !== "undefined") {
              newWorkflow.succeededProcessChains =
                  Math.max(0, newWorkflow.succeededProcessChains + workflow.succeededProcessChains)
            }
            newWorkflow.element = workflowToElement(newWorkflow)
            state = [...state.slice(0, i), newWorkflow, ...state.slice(i + 1)]
          }
        }
        return state
      }

      case "updateStatus": {
        for (let workflow of workflows) {
          if (workflow.currentStatus === workflow.newStatus) {
            continue
          }
          let i = state.findIndex(w => w.id === workflow.id)
          if (i >= 0) {
            let w = { ...state[i] }
            let n = 0
            if (workflow.currentStatus === "REGISTERED") {
              n = w.totalProcessChains - w.runningProcessChains -
                  w.failedProcessChains - w.succeededProcessChains -
                  w.cancelledProcessChains
            } else if (workflow.currentStatus === "RUNNING") {
              n = w.runningProcessChains
              w.runningProcessChains = 0
            } else if (workflow.currentStatus === "CANCELLED") {
              n = w.cancelledProcessChains
              w.cancelledProcessChains = 0
            } else if (workflow.currentStatus === "ERROR") {
              n = w.failedProcessChains
              w.failedProcessChains = 0
            } else if (workflow.currentStatus === "SUCCESS") {
              n = w.succeededProcessChains
              w.succeededProcessChains = 0
            }

            if (workflow.newStatus === "RUNNING") {
              w.runningProcessChains += n
            } else if (workflow.newStatus === "CANCELLED") {
              w.cancelledProcessChains += n
            } else if (workflow.newStatus === "ERROR") {
              w.failedProcessChains += n
            } else if (workflow.newStatus === "SUCCESS") {
              w.succeededProcessChains += n
            }

            w.element = workflowToElement(w)
            state = [...state.slice(0, i), w, ...state.slice(i + 1)]
          }
        }
        return state
      }

      default:
        return state
    }
  }
}

export default () => {
  // parse query params but do not use "next/router" because router.query
  // is empty on initial render
  let pageSize
  if (typeof window !== "undefined") {
    let params = new URLSearchParams(window.location.search)
    pageSize = params.get("size") || 10
  }

  const [workflows, updateWorkflows] = useReducer(updateWorkflowsReducer(pageSize), [])
  const eventBus = useContext(EventBusContext)
  const { data: fetchedWorkflows, error: fetchedWorkflowsError } =
      useSWR(pageSize && `${process.env.baseUrl}/workflows?size=${pageSize}`, fetcher)
  const oldFetchedWorkflows = useRef()

  useEffect(() => {
    function onSubmissionAdded(error, message) {
      let workflow = message.body
      workflow.justAdded = true
      updateWorkflows({ action: "unshift", workflows: [workflow] })
    }

    function onSubmissionStartTimeChanged(error, message) {
      updateWorkflows({
        action: "update", workflows: [{
          id: message.body.submissionId,
          startTime: message.body.startTime
        }]
      })
    }

    function onSubmissionEndTimeChanged(error, message) {
      updateWorkflows({
        action: "update", workflows: [{
          id: message.body.submissionId,
          endTime: message.body.endTime
        }]
      })
    }

    function onSubmissionStatusChanged(error, message) {
      updateWorkflows({
        action: "update", workflows: [{
          id: message.body.submissionId,
          status: message.body.status
        }]
      })
    }

    function onSubmissionErrorMessageChanged(error, message) {
      updateWorkflows({
        action: "update", workflows: [{
          id: message.body.submissionId,
          errorMessage: message.body.errorMessage
        }]
      })
    }

    function onProcessChainsAddedSize(error, message) {
      let pcsSize = message.body.processChainsSize
      let status = message.body.status

      let workflow = {
        id: message.body.submissionId,
        totalProcessChains: pcsSize
      }

      if (status === "RUNNING") {
        workflow.runningProcessChains = pcsSize
      } else if (status === "CANCELLED") {
        workflow.cancelledProcessChains = pcsSize
      } else if (status === "ERROR") {
        workflow.failedProcessChains = pcsSize
      } else if (status === "SUCCESS") {
        workflow.succeededProcessChains = pcsSize
      }

      updateWorkflows({ action: "updateAddProcessChains", workflows: [workflow] })
    }

    function onProcessChainStatusChanged(error, message) {
      let status = message.body.status
      let previousStatus = message.body.previousStatus

      let workflow = {
        id: message.body.submissionId,
        runningProcessChains: 0,
        cancelledProcessChains: 0,
        failedProcessChains: 0,
        succeededProcessChains: 0
      }

      if (previousStatus !== status) {
        if (previousStatus === "RUNNING") {
          workflow.runningProcessChains--
        } else if (previousStatus === "CANCELLED") {
          workflow.cancelledProcessChains--
        } else if (previousStatus === "ERROR") {
          workflow.failedProcessChains--
        } else if (previousStatus === "SUCCESS") {
          workflow.succeededProcessChains--
        }

        if (status === "RUNNING") {
          workflow.runningProcessChains++
        } else if (status === "CANCELLED") {
          workflow.cancelledProcessChains++
        } else if (status === "ERROR") {
          workflow.failedProcessChains++
        } else if (status === "SUCCESS") {
          workflow.succeededProcessChains++
        }

        updateWorkflows({ action: "updateAddProcessChains", workflows: [workflow] })
      }
    }

    function onProcessChainAllStatusChanged(error, message) {
      updateWorkflows({
        action: "updateStatus",
        workflows: [{
          id: message.body.submissionId,
          newStatus: message.body.newStatus,
          currentStatus: message.body.currentStatus
        }]
      })
    }

    if (eventBus) {
      eventBus.registerHandler(SUBMISSION_ADDED, onSubmissionAdded)
      eventBus.registerHandler(SUBMISSION_START_TIME_CHANGED, onSubmissionStartTimeChanged)
      eventBus.registerHandler(SUBMISSION_END_TIME_CHANGED, onSubmissionEndTimeChanged)
      eventBus.registerHandler(SUBMISSION_STATUS_CHANGED, onSubmissionStatusChanged)
      eventBus.registerHandler(SUBMISSION_ERROR_MESSAGE_CHANGED, onSubmissionErrorMessageChanged)
      eventBus.registerHandler(PROCESS_CHAINS_ADDED_SIZE, onProcessChainsAddedSize)
      eventBus.registerHandler(PROCESS_CHAIN_STATUS_CHANGED, onProcessChainStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onProcessChainAllStatusChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onProcessChainAllStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_STATUS_CHANGED, onProcessChainStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAINS_ADDED_SIZE, onProcessChainsAddedSize)
        eventBus.unregisterHandler(SUBMISSION_ERROR_MESSAGE_CHANGED, onSubmissionErrorMessageChanged)
        eventBus.unregisterHandler(SUBMISSION_STATUS_CHANGED, onSubmissionStatusChanged)
        eventBus.unregisterHandler(SUBMISSION_END_TIME_CHANGED, onSubmissionEndTimeChanged)
        eventBus.unregisterHandler(SUBMISSION_START_TIME_CHANGED, onSubmissionStartTimeChanged)
        eventBus.unregisterHandler(SUBMISSION_ADDED, onSubmissionAdded)
      }
    }
  }, [eventBus])

  let workflowError

  if (typeof fetchedWorkflowsError !== "undefined") {
    workflowError = <Alert error>Could not load workflows</Alert>
    console.error(fetchedWorkflowsError)
  } else if (typeof fetchedWorkflows !== "undefined") {
    if (fetchedWorkflows !== oldFetchedWorkflows.current) {
      oldFetchedWorkflows.current = fetchedWorkflows
      updateWorkflows({ action: "push", workflows: fetchedWorkflows })
    }
  }

  return (
    <Page>
      <h1>Workflows</h1>
      {workflows.map(w => w.element)}
      {workflowError}
    </Page>
  )
}
