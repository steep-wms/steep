import { deleteRunInformation } from "./processChainUtil"
import makeListContext from "../lib/ListContext"
import { produce } from "immer"

import {
  PROCESS_CHAINS_ADDED,
  PROCESS_CHAIN_RUN_ADDED,
  PROCESS_CHAIN_RUN_FINISHED,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED,
  PROCESS_CHAIN_PRIORITY_CHANGED,
  PROCESS_CHAIN_ALL_PRIORITY_CHANGED,
  PROCESS_CHAIN_PROGRESS_CHANGED,
  SUBMISSIONS_DELETED
} from "../../components/lib/EventBusMessages"

const ADD_MESSAGES = {
  [PROCESS_CHAINS_ADDED]: body => {
    for (let pc of body.processChains) {
      pc.status = body.status
      pc.submissionId = body.submissionId
    }
    return body.processChains
  }
}

const UPDATE_MESSAGES = {
  [PROCESS_CHAIN_RUN_ADDED]: body => body,
  [PROCESS_CHAIN_RUN_FINISHED]: body => body,
  [PROCESS_CHAIN_PROGRESS_CHANGED]: body => ({
    id: body.processChainId,
    estimatedProgress: body.estimatedProgress
  }),
  [PROCESS_CHAIN_STATUS_CHANGED]: body => ({
    id: body.processChainId,
    status: body.status,
    submissionId: body.submissionId
  }),
  [PROCESS_CHAIN_ALL_STATUS_CHANGED]: body => ({
    currentStatus: body.currentStatus,
    status: body.newStatus,
    submissionId: body.submissionId
  }),
  [PROCESS_CHAIN_PRIORITY_CHANGED]: body => ({
    id: body.processChainId,
    priority: body.priority
  }),
  [PROCESS_CHAIN_ALL_PRIORITY_CHANGED]: body => ({
    priority: body.priority,
    submissionId: body.submissionId
  }),
  [SUBMISSIONS_DELETED]: body =>
    body.submissionIds.map(submissionId => ({
      submissionId,
      deleted: true
    }))
}

function reducer(state, { action, items }, next) {
  if (action === "update" && items.length > 0 && state.items !== undefined) {
    if (items[0].id === undefined && items[0].submissionId !== undefined) {
      // update all process chains of a submission
      return produce(state, draft => {
        for (let item of items) {
          for (let pc of draft.items) {
            if (pc.submissionId === item.submissionId) {
              if (item.deleted) {
                pc.deleted = item.deleted
              } else if (
                item.currentStatus !== undefined &&
                pc.status === item.currentStatus
              ) {
                if (item.status === "REGISTERED" || item.status === "PAUSED") {
                  // remove run information
                  deleteRunInformation(pc)
                }
                pc.status = item.status
              } else if (
                item.priority !== undefined &&
                (pc.status === "REGISTERED" ||
                  pc.status === "RUNNING" ||
                  pc.status === "PAUSED")
              ) {
                pc.priority = item.priority
              }
            }
          }
        }
      })
    }

    if (
      items[0].id !== undefined &&
      (items[0].status === "REGISTERED" || items[0].status === "PAUSED")
    ) {
      // remove run information
      state = produce(state, draft => {
        for (let item of items) {
          for (let pc of draft.items) {
            if (pc.id === item.id) {
              deleteRunInformation(pc)
            }
          }
        }
      })
    }

    if (items[0].runNumber !== undefined && items[0].startTime !== undefined) {
      // add process chain run:
      // update process chain and overwrite run attributes but only if it does
      // not have a runNumber
      return produce(state, draft => {
        for (let item of items) {
          for (let pc of draft.items) {
            if (pc.id !== item.processChainId || pc.runNumber !== undefined) {
              // do not update this process chain
              continue
            }

            // remove all attributes related to runs (but keep status)
            let status = pc.status
            deleteRunInformation(pc)
            pc.status = status

            // add attributes from run
            pc.startTime = item.startTime
            pc.runNumber = item.runNumber
          }
        }
      })
    }

    if (items[0].runNumber !== undefined && items[0].endTime !== undefined) {
      // finish process chain run:
      // update process chains if runNumber matches
      return produce(state, draft => {
        for (let item of items) {
          for (let pc of draft.items) {
            if (
              pc.id !== item.processChainId ||
              pc.runNumber !== item.runNumber
            ) {
              continue
            }

            pc.endTime = item.endTime
            pc.status = item.status
            pc.errorMessage = item.errorMessage
            pc.autoResumeAfter = item.autoResumeAfter
          }
        }
      })
    }
  }

  return next(state, { action, items })
}

const ListContext = makeListContext()

const Provider = props => {
  let reducers = [...(props.reducers || []), reducer]
  return (
    <ListContext.Provider
      {...props}
      addMessages={ADD_MESSAGES}
      updateMessages={UPDATE_MESSAGES}
      reducers={reducers}
    />
  )
}

const ProcessChainContext = {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}

export default ProcessChainContext
