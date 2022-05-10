import ListContext from "../lib/ListContext"

import {
  PROCESS_CHAINS_ADDED,
  PROCESS_CHAIN_START_TIME_CHANGED,
  PROCESS_CHAIN_END_TIME_CHANGED,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED,
  PROCESS_CHAIN_PRIORITY_CHANGED,
  PROCESS_CHAIN_ALL_PRIORITY_CHANGED,
  PROCESS_CHAIN_ERROR_MESSAGE_CHANGED,
  PROCESS_CHAIN_PROGRESS_CHANGED,
  SUBMISSIONS_DELETED
} from "../../components/lib/EventBusMessages"

const ADD_MESSAGES = {
  [PROCESS_CHAINS_ADDED]: (body) => {
    for (let pc of body.processChains) {
      pc.status = body.status
      pc.submissionId = body.submissionId
    }
    return body.processChains
  }
}

const UPDATE_MESSAGES = {
  [PROCESS_CHAIN_START_TIME_CHANGED]: (body) => ({
    id: body.processChainId,
    startTime: body.startTime
  }),
  [PROCESS_CHAIN_END_TIME_CHANGED]: (body) => ({
    id: body.processChainId,
    endTime: body.endTime
  }),
  [PROCESS_CHAIN_ERROR_MESSAGE_CHANGED]: (body) => ({
    id: body.processChainId,
    errorMessage: body.errorMessage
  }),
  [PROCESS_CHAIN_PROGRESS_CHANGED]: (body) => ({
    id: body.processChainId,
    estimatedProgress: body.estimatedProgress
  }),
  [PROCESS_CHAIN_STATUS_CHANGED]: (body) => ({
    id: body.processChainId,
    status: body.status,
    submissionId: body.submissionId
  }),
  [PROCESS_CHAIN_ALL_STATUS_CHANGED]: (body) => ({
    currentStatus: body.currentStatus,
    status: body.newStatus,
    submissionId: body.submissionId
  }),
  [PROCESS_CHAIN_PRIORITY_CHANGED]: (body) => ({
    id: body.processChainId,
    priority: body.priority
  }),
  [PROCESS_CHAIN_ALL_PRIORITY_CHANGED]: (body) => ({
    priority: body.priority,
    submissionId: body.submissionId
  }),
  [SUBMISSIONS_DELETED]: (body) => body.submissionIds.map(submissionId => ({
    submissionId,
    deleted: true
  }))
}

function reducer(state, { action, items }, next) {
  if (action === "update" && items.length > 0 && items[0].id === undefined &&
      items[0].submissionId !== undefined) {
    // update all process chains of a submission
    if (state.items !== undefined) {
      for (let item of items) {
        for (let i = 0; i < state.items.length; ++i) {
          let pc = state.items[i]
          if (pc.submissionId === item.submissionId) {
            let newItems = [...state.items]
            if (item.deleted) {
              newItems[i] = { ...pc, deleted: item.deleted }
            } else if (item.currentStatus !== undefined && pc.status === item.currentStatus) {
              newItems[i] = { ...pc, status: item.status }
            } else if (item.priority !== undefined &&
                (pc.status === "REGISTERED" || pc.status === "RUNNING")) {
              newItems[i] = { ...pc, priority: item.priority }
            }
            state = { ...state, items: newItems }
          }
        }
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

const ProcessChainContext = {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}

export default ProcessChainContext
