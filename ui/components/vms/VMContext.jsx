import EventBusContext from "../lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import { createContext, useContext, useEffect, useReducer } from "react"
import listItemUpdateReducer from "../lib/listitem-update-reducer"

import {
  VM_ADDED,
  VM_CREATIONTIME_CHANGED,
  VM_AGENTJOINTIME_CHANGED,
  VM_DESTRUCTIONTIME_CHANGED,
  VM_STATUS_CHANGED,
  VM_EXTERNALID_CHANGED,
  VM_IPADDRESS_CHANGED,
  VM_REASON_CHANGED
} from "../lib/EventBusMessages"

const State = createContext()
const Dispatch = createContext()

function updateVMsReducer(pageSize, onVMChanged) {
  let liur = listItemUpdateReducer(pageSize, (vm) => {
    onVMChanged && onVMChanged(vm)
  })

  return (state, { action = "unshift", vms }) => {
    state = state || []
    return liur(state, { action, items: vms })
  }
}

const Provider = ({ pageSize, onVMChanged, allowAdd = true, children }) => {
  const [vms, updateVMs] = useReducer(updateVMsReducer(pageSize, onVMChanged))
  const eventBus = useContext(EventBusContext)

  useEffect(() => {
    function onAdded(error, message) {
      let vm = message.body
      vm.justAdded = true
      updateVMs({ action: "unshift", vms: [vm] })
    }

    function onCreationTimeChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          creationTime: message.body.creationTime
        }]
      })
    }

    function onAgentJoinTimeChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          agentJoinTime: message.body.agentJoinTime
        }]
      })
    }

    function onDestructionTimeChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          destructionTime: message.body.destructionTime
        }]
      })
    }

    function onStatusChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          status: message.body.status
        }]
      })
    }

    function onExternalIdChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          externalId: message.body.externalId
        }]
      })
    }

    function onIpAddressChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          ipAddress: message.body.ipAddress
        }]
      })
    }

    function onReasonChanged(error, message) {
      updateVMs({
        action: "update", vms: [{
          id: message.body.id,
          reason: message.body.reason
        }]
      })
    }

    if (eventBus) {
      if (allowAdd) {
        eventBus.registerHandler(VM_ADDED, onAdded)
      }
      eventBus.registerHandler(VM_CREATIONTIME_CHANGED, onCreationTimeChanged)
      eventBus.registerHandler(VM_AGENTJOINTIME_CHANGED, onAgentJoinTimeChanged)
      eventBus.registerHandler(VM_DESTRUCTIONTIME_CHANGED, onDestructionTimeChanged)
      eventBus.registerHandler(VM_STATUS_CHANGED, onStatusChanged)
      eventBus.registerHandler(VM_EXTERNALID_CHANGED, onExternalIdChanged)
      eventBus.registerHandler(VM_IPADDRESS_CHANGED, onIpAddressChanged)
      eventBus.registerHandler(VM_REASON_CHANGED, onReasonChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(VM_REASON_CHANGED, onReasonChanged)
        eventBus.unregisterHandler(VM_IPADDRESS_CHANGED, onIpAddressChanged)
        eventBus.unregisterHandler(VM_EXTERNALID_CHANGED, onExternalIdChanged)
        eventBus.unregisterHandler(VM_STATUS_CHANGED, onStatusChanged)
        eventBus.unregisterHandler(VM_DESTRUCTIONTIME_CHANGED, onDestructionTimeChanged)
        eventBus.unregisterHandler(VM_AGENTJOINTIME_CHANGED, onAgentJoinTimeChanged)
        eventBus.unregisterHandler(VM_CREATIONTIME_CHANGED, onCreationTimeChanged)
        if (allowAdd) {
          eventBus.unregisterHandler(VM_ADDED, onAdded)
        }
      }
    }
  }, [eventBus, allowAdd])

  return (
    <State.Provider value={vms}>
      <Dispatch.Provider value={updateVMs}>{children}</Dispatch.Provider>
    </State.Provider>
  )
}

export default {
  State,
  Dispatch,
  Provider
}
