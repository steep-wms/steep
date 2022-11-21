import ListContext from "../lib/ListContext"

import {
  SUBMISSION_ADDED,
  SUBMISSION_START_TIME_CHANGED,
  SUBMISSION_END_TIME_CHANGED,
  SUBMISSION_STATUS_CHANGED,
  SUBMISSION_PRIORITY_CHANGED,
  SUBMISSION_ERROR_MESSAGE_CHANGED,
  SUBMISSIONS_DELETED,
  PROCESS_CHAINS_ADDED_SIZE,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED
} from "../lib/EventBusMessages"

function initWorkflow(w) {
  w.runningProcessChains = w.runningProcessChains || 0
  w.pausedProcessChains = w.pausedProcessChains || 0
  w.succeededProcessChains = w.succeededProcessChains || 0
  w.cancelledProcessChains = w.cancelledProcessChains || 0
  w.failedProcessChains = w.failedProcessChains || 0
  w.totalProcessChains = w.totalProcessChains || 0
}

const ADD_MESSAGES = {
  [SUBMISSION_ADDED]: (body) => {
    let w = body
    initWorkflow(w)
    return [w]
  }
}

const UPDATE_MESSAGES = {
  [SUBMISSION_START_TIME_CHANGED]: (body) => ({
    id: body.submissionId,
    startTime: body.startTime
  }),
  [SUBMISSION_END_TIME_CHANGED]: (body) => ({
    id: body.submissionId,
    endTime: body.endTime
  }),
  [SUBMISSION_STATUS_CHANGED]: (body) => ({
    id: body.submissionId,
    status: body.status
  }),
  [SUBMISSION_PRIORITY_CHANGED]: (body) => ({
    id: body.submissionId,
    priority: body.priority
  }),
  [SUBMISSION_ERROR_MESSAGE_CHANGED]: (body) => ({
    id: body.submissionId,
    errorMessage: body.errorMessage
  }),
  [SUBMISSIONS_DELETED]: (body) => body.submissionIds.map(submissionId => ({
    id: submissionId,
    deleted: true
  })),
  [PROCESS_CHAINS_ADDED_SIZE]: (body) => {
    let pcsSize = body.processChainsSize
    let status = body.status

    let workflow = {
      id: body.submissionId,
      totalProcessChains: pcsSize
    }

    if (status === "RUNNING") {
      workflow.runningProcessChains += pcsSize
    } else if (status === "PAUSED") {
      workflow.pausedProcessChains += pcsSize
    } else if (status === "CANCELLED") {
      workflow.cancelledProcessChains += pcsSize
    } else if (status === "ERROR") {
      workflow.failedProcessChains += pcsSize
    } else if (status === "SUCCESS") {
      workflow.succeededProcessChains += pcsSize
    }

    return workflow
  },
  [PROCESS_CHAIN_STATUS_CHANGED]: (body) => {
    let status = body.status
    let previousStatus = body.previousStatus

    let workflow = {
      id: body.submissionId,
      runningProcessChains: 0,
      pausedProcessChains: 0,
      cancelledProcessChains: 0,
      failedProcessChains: 0,
      succeededProcessChains: 0
    }

    if (previousStatus !== status) {
      if (previousStatus === "RUNNING") {
        workflow.runningProcessChains--
      } else if (previousStatus === "PAUSED") {
        workflow.pausedProcessChains--
      } else if (previousStatus === "CANCELLED") {
        workflow.cancelledProcessChains--
      } else if (previousStatus === "ERROR") {
        workflow.failedProcessChains--
      } else if (previousStatus === "SUCCESS") {
        workflow.succeededProcessChains--
      }

      if (status === "RUNNING") {
        workflow.runningProcessChains++
      } else if (status === "PAUSED") {
        workflow.pausedProcessChains++
      } else if (status === "CANCELLED") {
        workflow.cancelledProcessChains++
      } else if (status === "ERROR") {
        workflow.failedProcessChains++
      } else if (status === "SUCCESS") {
        workflow.succeededProcessChains++
      }
    }

    return workflow
  },
  [PROCESS_CHAIN_ALL_STATUS_CHANGED]: (body) => ({
    id: body.submissionId,
    newStatus: body.newStatus,
    currentStatus: body.currentStatus
  })
}

function reducer(state, { action, items }, next) {
  if (action === "update" && items.length > 0 &&
      (items[0].totalProcessChains !== undefined ||
        items[0].runningProcessChains !== undefined ||
        items[0].pausedProcessChains !== undefined ||
        items[0].cancelledProcessChains !== undefined ||
        items[0].failedProcessChains !== undefined ||
        items[0].succeededProcessChains !== undefined)) {
    // add process chains or change status of process chains
    if (state.items !== undefined) {
      for (let item of items) {
        let i = state.items.findIndex(w => w.id === item.id)
        if (i < 0) {
          continue
        }

        let newItem = { ...state.items[i] }
        if (item.totalProcessChains !== undefined) {
          newItem.totalProcessChains =
              Math.max(0, newItem.totalProcessChains + item.totalProcessChains)
        }
        if (item.runningProcessChains !== undefined) {
          newItem.runningProcessChains =
              Math.max(0, newItem.runningProcessChains + item.runningProcessChains)
        }
        if (item.pausedProcessChains !== undefined) {
          newItem.pausedProcessChains =
              Math.max(0, newItem.pausedProcessChains + item.pausedProcessChains)
        }
        if (item.cancelledProcessChains !== undefined) {
          newItem.cancelledProcessChains =
              Math.max(0, newItem.cancelledProcessChains + item.cancelledProcessChains)
        }
        if (item.failedProcessChains !== undefined) {
          newItem.failedProcessChains =
              Math.max(0, newItem.failedProcessChains + item.failedProcessChains)
        }
        if (item.succeededProcessChains !== undefined) {
          newItem.succeededProcessChains =
              Math.max(0, newItem.succeededProcessChains + item.succeededProcessChains)
        }

        let newItems = [...state.items]
        newItems[i] = newItem
        state = { ...state, items: newItems }
      }
    }
    return state
  }

  if (action === "update" && items.length > 0 &&
      items[0].currentStatus !== undefined && items[0].newStatus !== undefined) {
    // change status of all process chains
    if (state.items !== undefined) {
      for (let item of items) {
        if (item.currentStatus === item.newStatus) {
          continue
        }

        let i = state.items.findIndex(w => w.id === item.id)
        if (i < 0) {
          continue
        }

        let newItem = { ...state.items[i] }
        let n = 0
        if (item.currentStatus === "REGISTERED") {
          n = newItem.totalProcessChains - newItem.runningProcessChains -
              newItem.pausedProcessChains - newItem.failedProcessChains -
              newItem.succeededProcessChains - newItem.cancelledProcessChains
        } else if (item.currentStatus === "RUNNING") {
          n = newItem.runningProcessChains
          newItem.runningProcessChains = 0
        } else if (item.currentStatus === "PAUSED") {
          n = newItem.pausedProcessChains
          newItem.pausedProcessChains = 0
        } else if (item.currentStatus === "CANCELLED") {
          n = newItem.cancelledProcessChains
          newItem.cancelledProcessChains = 0
        } else if (item.currentStatus === "ERROR") {
          n = newItem.failedProcessChains
          newItem.failedProcessChains = 0
        } else if (item.currentStatus === "SUCCESS") {
          n = newItem.succeededProcessChains
          newItem.succeededProcessChains = 0
        }

        if (item.newStatus === "RUNNING") {
          newItem.runningProcessChains += n
        } else if (item.newStatus === "PAUSED") {
          newItem.pausedProcessChains += n
        } else if (item.newStatus === "CANCELLED") {
          newItem.cancelledProcessChains += n
        } else if (item.newStatus === "ERROR") {
          newItem.failedProcessChains += n
        } else if (item.newStatus === "SUCCESS") {
          newItem.succeededProcessChains += n
        }

        let newItems = [...state.items]
        newItems[i] = newItem
        state = { ...state, items: newItems }
      }
    }
    return state
  }

  return next(state, { action, items })
}

const Provider = (props) => {
  let reducers = [...(props.reducers || []), reducer]
  return <ListContext.Provider {...props} addMessages={ADD_MESSAGES}
      updateMessages={UPDATE_MESSAGES} reducers={reducers} />
}

const WorkflowContext = {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}

export default WorkflowContext
