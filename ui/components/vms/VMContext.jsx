import makeListContext from "../lib/ListContext"

import {
  VM_ADDED,
  VM_CREATIONTIME_CHANGED,
  VM_AGENTJOINTIME_CHANGED,
  VM_DESTRUCTIONTIME_CHANGED,
  VM_STATUS_CHANGED,
  VM_EXTERNALID_CHANGED,
  VM_IPADDRESS_CHANGED,
  VM_REASON_CHANGED,
  VMS_DELETED
} from "../lib/EventBusMessages"

const ADD_MESSAGES = {
  [VM_ADDED]: (body) => [body]
}

const UPDATE_MESSAGES = {
  [VM_CREATIONTIME_CHANGED]: (body) => ({
    id: body.id,
    creationTime: body.creationTime
  }),
  [VM_AGENTJOINTIME_CHANGED]: (body) => ({
    id: body.id,
    agentJoinTime: body.agentJoinTime
  }),
  [VM_DESTRUCTIONTIME_CHANGED]: (body) => ({
    id: body.id,
    destructionTime: body.destructionTime
  }),
  [VM_STATUS_CHANGED]: (body) => ({
    id: body.id,
    status: body.status
  }),
  [VM_EXTERNALID_CHANGED]: (body) => ({
    id: body.id,
    externalId: body.externalId
  }),
  [VM_IPADDRESS_CHANGED]: (body) => ({
    id: body.id,
    ipAddress: body.ipAddress
  }),
  [VM_REASON_CHANGED]: (body) => ({
    id: body.id,
    reason: body.reason
  }),
  [VMS_DELETED]: (body) => body.vmIds.map(vmId => ({
    id: vmId,
    deleted: true
  }))
}

const ListContext = makeListContext()

const Provider = (props) => (
  <ListContext.Provider {...props} addMessages={ADD_MESSAGES}
      updateMessages={UPDATE_MESSAGES} />
)

const VMContext = {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}

export default VMContext
