package db.migration

/**
 * Recursively search the given JSON object for store actions and remove
 * them with a warning. Support for store actions was dropped in workflow
 * API version 4.0.0.
 */
fun removeStoreActions(json: Map<*, *>): Boolean {
  var changed = false

  val actions = json["actions"]
  if (actions is MutableList<*>) {
    val iterator = actions.iterator()
    while (iterator.hasNext()) {
      val a = iterator.next()
      if (a is Map<*, *>) {
        val type = a["type"]
        if ("for" == type) {
          if (removeStoreActions(a)) {
            changed = true
          }
        } else if ("store" == type) {
          iterator.remove()
          changed = true
        }
      }
    }
  }

  return changed
}

/**
 * Recursively search the given JSON object for action parameters and merge
 * them into the action's inputs with a warning. Support for action
 * parameters will be dropped in workflow API version 5.0.0.
 */
fun removeExecuteActionParameters(json: Map<*, *>): Boolean {
  var changed = false

  val actions = json["actions"]
  if (actions is MutableList<*>) {
    val iterator = actions.iterator()
    while (iterator.hasNext()) {
      val a = iterator.next()
      if (a is MutableMap<*, *>) {
        val type = a["type"]
        if ("for" == type) {
          if (removeExecuteActionParameters(a)) {
            changed = true
          }
        } else if ("execute" == type) {
          val parameters = a["parameters"]
          if (parameters != null) {
            val newInputs = (a["inputs"] as MutableList<*>? ?: mutableListOf<Any>()) +
                (parameters as MutableList<*>? ?: mutableListOf<Any>())
            @Suppress("UNCHECKED_CAST")
            (a as MutableMap<String, Any>)["inputs"] = newInputs
            a.remove("parameters")
            changed = true
          }
        }
      }
    }
  }

  return changed
}

/**
 * Renames the argument type `argument` in process chains to `input`. Support
 * for this type will be dropped in workflow API version 5.0.0.
 * @param json the JSON object to modify in place
 * @return `true` if the object was modified
 */
fun processChainArgumentsToInputs(json: Map<*, *>): Boolean {
  var changed = false

  val executables = json["executables"]
  if (executables is MutableList<*>) {
    for (executable in executables) {
      if (executable is MutableMap<*, *>) {
        val arguments = executable["arguments"]
        if (arguments is MutableList<*>) {
          for (argument in arguments) {
            if (argument is MutableMap<*, *>) {
              if (argument["type"] == "argument") {
                @Suppress("UNCHECKED_CAST")
                (argument as MutableMap<String, Any>)["type"] = "input"
                changed = true
              }
            }
          }
        }
      }
    }
  }

  return changed
}
