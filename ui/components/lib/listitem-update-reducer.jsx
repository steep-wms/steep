export default (pageSize, onItemChanged) => (state, { action = "unshift", items }) => {
  if (action === "set") {
    if (items === undefined) {
      return undefined
    }
    action = "unshift"
  }

  switch (action) {
    case "update": {
      if (state !== undefined) {
        for (let item of items) {
          let i = state.findIndex(w => w.id === item.id)
          if (i >= 0) {
            let oldItem = state[i]
            let newItem = { ...oldItem, ...item }
            onItemChanged && onItemChanged(newItem, oldItem)
            state = [...state.slice(0, i), newItem, ...state.slice(i + 1)]
          }
        }
      }
      return state
    }

    case "unshift": {
      state = state || []

      let itemsToAdd = []
      for (let item of items) {
        if (state.findIndex(w => w.id === item.id) < 0) {
          itemsToAdd.unshift(item)
        }
      }

      if (pageSize !== undefined) {
        itemsToAdd = itemsToAdd.slice(0, pageSize)
        state = state.slice(0, pageSize - itemsToAdd.length)
      }

      if (onItemChanged) {
        for (let item of itemsToAdd) {
          onItemChanged(item)
        }
      }

      return [...itemsToAdd, ...state]
    }

    default:
      return state
  }
}
