import makeListContext from "../lib/ListContext"
import { produce } from "immer"

import {
  PROCESS_CHAIN_RUN_ADDED,
  PROCESS_CHAIN_RUN_FINISHED,
  PROCESS_CHAIN_LAST_RUN_DELETED,
  PROCESS_CHAIN_ALL_RUNS_DELETED
} from "../../components/lib/EventBusMessages"

const ADD_MESSAGES = {
  [PROCESS_CHAIN_RUN_ADDED]: body => [{ ...body, id: body.runNumber }]
}

const UPDATE_MESSAGES = {
  [PROCESS_CHAIN_RUN_FINISHED]: body => ({ ...body, id: body.runNumber }),
  [PROCESS_CHAIN_LAST_RUN_DELETED]: body => ({ ...body, deleteLastRun: true }),
  [PROCESS_CHAIN_ALL_RUNS_DELETED]: body => ({ ...body, deleteAllRuns: true })
}

function reducer(processChainId) {
  return (state, { action, items }, next) => {
    if (items !== undefined) {
      if (action === "set") {
        items = produce(items, draft => {
          for (let i = 0; i < draft.length; ++i) {
            let item = draft[i]
            item.processChainId = processChainId
            item.runNumber = i + 1
            item.id = item.runNumber
          }
          draft.reverse()
        })
      }

      // skip items that don't match the current process chain
      items = items.filter(i => i.processChainId === processChainId)

      if (action === "update" && items.length > 0 && items[0].deleteLastRun) {
        return produce(state, draft => {
          if (draft.items !== undefined && draft.items.length > 0) {
            draft.items.shift()
          }
        })
      }

      if (action === "update" && items.length > 0 && items[0].deleteAllRuns) {
        return produce(state, draft => {
          draft.items = []
        })
      }
    }

    return next(state, { action, items })
  }
}

const ListContext = makeListContext()

const Provider = props => {
  let reducers = [...(props.reducers || []), reducer(props.processChainId)]
  return (
    <ListContext.Provider
      {...props}
      addMessages={ADD_MESSAGES}
      updateMessages={UPDATE_MESSAGES}
      reducers={reducers}
    />
  )
}

const ProcessChainRunContext = {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}

export default ProcessChainRunContext
