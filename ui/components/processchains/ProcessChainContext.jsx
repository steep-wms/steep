import EventBusContext from "../lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import { createContext, useContext, useEffect, useReducer } from "react"
import listItemUpdateReducer from "../lib/listitem-update-reducer"

import {
  PROCESS_CHAINS_ADDED,
  PROCESS_CHAIN_START_TIME_CHANGED,
  PROCESS_CHAIN_END_TIME_CHANGED,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED,
  PROCESS_CHAIN_ERROR_MESSAGE_CHANGED
} from "../../components/lib/EventBusMessages"

const State = createContext()
const Dispatch = createContext()

function initProcessChain(processChain) {
  processChain.startTime = processChain.startTime || null
  processChain.endTime = processChain.endTime || null
}

function updateProcessChainsReducer(pageSize, onProcessChainChanged) {
  let liur = listItemUpdateReducer(pageSize, (processChain) => {
    initProcessChain(processChain)
    onProcessChainChanged && onProcessChainChanged(processChain)
  })

  return (state, { action = "unshift", processChains }) => {
    state = state || []
    switch (action) {
      case "set":
      case "update":
      case "unshift":
      case "push":
        return liur(state, { action, items: processChains })

      case "updateStatus": {
        for (let update of processChains) {
          for (let i = 0; i < state.length; ++i) {
            let pc = state[i]
            if (pc.submissionId === update.submissionId && pc.status === update.currentStatus) {
              let newProcessChain = { ...pc, status: update.newStatus }
              onProcessChainChanged && onProcessChainChanged(newProcessChain)
              state = [...state.slice(0, i), newProcessChain, ...state.slice(i + 1)]
            }
          }
        }
        return state
      }

      default:
        return state
    }
  }
}

const Provider = ({ pageSize, onProcessChainChanged, allowAdd = true, children }) => {
  const [processChains, updateProcessChains] = useReducer(
    updateProcessChainsReducer(pageSize, onProcessChainChanged))
  const eventBus = useContext(EventBusContext)

  useEffect(() => {
    function onProcessChainsAdded(error, message) {
      let processChains = message.body.processChains
      let status = message.body.status
      for (let processChain of processChains) {
        processChain.status = status
        initProcessChain(processChain)
        processChain.justAdded = true
      }
      updateProcessChains({ action: "unshift", processChains })
    }

    function onProcessChainStartTimeChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          startTime: message.body.startTime
        }]
      })
    }

    function onProcessChainEndTimeChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          endTime: message.body.endTime
        }]
      })
    }

    function onProcessChainStatusChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          status: message.body.status
        }]
      })
    }

    function onProcessChainAllStatusChanged(error, message) {
      updateProcessChains({
        action: "updateStatus", processChains: [{
          submissionId: message.body.submissionId,
          currentStatus: message.body.currentStatus,
          newStatus: message.body.newStatus
        }]
      })
    }

    function onProcessChainErrorMessageChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          errorMessage: message.body.errorMessage
        }]
      })
    }

    if (eventBus) {
      if (allowAdd) {
        eventBus.registerHandler(PROCESS_CHAINS_ADDED, onProcessChainsAdded)
      }
      eventBus.registerHandler(PROCESS_CHAIN_START_TIME_CHANGED, onProcessChainStartTimeChanged)
      eventBus.registerHandler(PROCESS_CHAIN_END_TIME_CHANGED, onProcessChainEndTimeChanged)
      eventBus.registerHandler(PROCESS_CHAIN_STATUS_CHANGED, onProcessChainStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onProcessChainAllStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ERROR_MESSAGE_CHANGED, onProcessChainErrorMessageChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(PROCESS_CHAIN_ERROR_MESSAGE_CHANGED, onProcessChainErrorMessageChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onProcessChainAllStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_STATUS_CHANGED, onProcessChainStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_END_TIME_CHANGED, onProcessChainEndTimeChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_START_TIME_CHANGED, onProcessChainStartTimeChanged)
        if (allowAdd) {
          eventBus.unregisterHandler(PROCESS_CHAINS_ADDED, onProcessChainsAdded)
        }
      }
    }
  }, [eventBus, allowAdd])

  return (
    <State.Provider value={processChains}>
      <Dispatch.Provider value={updateProcessChains}>{children}</Dispatch.Provider>
    </State.Provider>
  )
}

export default {
  State,
  Dispatch,
  Provider
}
