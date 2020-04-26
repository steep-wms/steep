import EventBusContext from "../lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import { createContext, useContext, useEffect, useReducer } from "react"
import listItemUpdateReducer from "../lib/listitem-update-reducer"

import {
  SUBMISSION_ADDED,
  SUBMISSION_START_TIME_CHANGED,
  SUBMISSION_END_TIME_CHANGED,
  SUBMISSION_STATUS_CHANGED,
  SUBMISSION_ERROR_MESSAGE_CHANGED,
  PROCESS_CHAINS_ADDED_SIZE,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED
} from "../lib/EventBusMessages"

const State = createContext()
const Dispatch = createContext()

function initWorkflow(w) {
  w.runningProcessChains = w.runningProcessChains || 0
  w.succeededProcessChains = w.succeededProcessChains || 0
  w.cancelledProcessChains = w.cancelledProcessChains || 0
  w.failedProcessChains = w.failedProcessChains || 0
  w.totalProcessChains = w.totalProcessChains || 0
  w.startTime = w.startTime || null
  w.endTime = w.endTime || null
}

function updateWorkflowsReducer(pageSize, onWorkflowChanged) {
  let liur = listItemUpdateReducer(pageSize, (workflow) => {
    initWorkflow(workflow)
    onWorkflowChanged && onWorkflowChanged(workflow)
  })

  return (state, { action = "unshift", workflows }) => {
    switch (action) {
      case "set":
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
            onWorkflowChanged && onWorkflowChanged(newWorkflow)
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

            onWorkflowChanged && onWorkflowChanged(w)
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

const Provider = ({ pageSize, onWorkflowChanged, allowAdd = true, children }) => {
  const [workflows, updateWorkflows] = useReducer(
    updateWorkflowsReducer(pageSize, onWorkflowChanged), [])
  const eventBus = useContext(EventBusContext)

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
      if (allowAdd) {
        eventBus.registerHandler(SUBMISSION_ADDED, onSubmissionAdded)
      }
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
        if (allowAdd) {
          eventBus.unregisterHandler(SUBMISSION_ADDED, onSubmissionAdded)
        }
      }
    }
  }, [eventBus, allowAdd])

  return (
    <State.Provider value={workflows}>
      <Dispatch.Provider value={updateWorkflows}>{children}</Dispatch.Provider>
    </State.Provider>
  )
}

export default {
  State,
  Dispatch,
  Provider
}
