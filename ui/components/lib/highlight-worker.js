import hljs from "highlight.js/lib/core"
import json from "highlight.js/lib/languages/json"
import yaml from "highlight.js/lib/languages/yaml"

/**
 * Based on HastEmitter from lowlight (https://github.com/wooorm/lowlight) but
 * modified to issue blocks of highlighted code to the caller of this worker.
 * The original code is released under the MIT license by Titus Wormer
 */
class HastEmitter {
  constructor(options) {
    this.options = options
    this.root = {
      type: "element",
      tagName: "span",
      children: []
    }
    this.stack = [this.root]
    this.lineCount = 0
  }

  clearStack(stack) {
    let newStack = []
    let o = stack[stack.length - 1]
    if (o.children !== undefined) {
      o = { ...o, children: [] }
    }
    newStack.push(o)
    for (let i = stack.length - 2; i >= 0; --i) {
      let newParent = { ...stack[i], children: [o] }
      newStack.push(newParent)
      o = newParent
    }
    return newStack
  }

  addText(value) {
    if (value.length === 0) {
      return
    }

    let current = this.stack[this.stack.length - 1]
    let tail = current.children[current.children.length - 1]

    let rest
    let lf = -1
    do {
      lf = value.indexOf("\n", lf + 1)
      if (lf >= 0) {
        this.lineCount++
        if (this.lineCount === self.blockSize) {
          rest = value.substring(lf + 1)
          value = value.substring(0, lf + 1)
          this.lineCount = 0
          break
        }
      }
    } while (lf >= 0)

    if (tail && tail.type === "text") {
      tail.value += value
    } else {
      current.children.push({
        type: "text",
        value
      })
    }

    if (rest !== undefined) {
      self.postMessage({
        hast: this.stack[0]
      })
      this.stack = this.clearStack(this.stack)

      let current = this.stack[this.stack.length - 1]
      if (rest.length > 0) {
        current.children.push({
          type: "text",
          value: rest
        })
      }
    }
  }

  addKeyword(value, name) {
    this.openNode(name)
    this.addText(value)
    this.closeNode()
  }

  addSublanguage(other, name) {
    let current = this.stack[this.stack.length - 1]
    let results = other.root.children

    if (name) {
      current.children.push({
        type: "element",
        tagName: "span",
        properties: {
          className: [name]
        },
        children: results
      })
    } else {
      current.children.push(...results)
    }
  }

  openNode(name) {
    // First “class” gets the prefix. Rest gets a repeated underscore suffix.
    // See: <https://github.com/highlightjs/highlight.js/commit/51806aa>
    // See: <https://github.com/wooorm/lowlight/issues/43>
    let className = name
      .split(".")
      .map((d, i) => (i ? d + "_".repeat(i) : this.options.classPrefix + d))

    let current = this.stack[this.stack.length - 1]
    let child = {
      type: "element",
      tagName: "span",
      properties: {
        className
      },
      children: []
    }

    current.children.push(child)
    this.stack.push(child)
  }

  closeNode() {
    this.stack.pop()
  }

  closeAllNodes() {
    // nothing to do here
  }

  finalize() {
    self.postMessage({
      hast: this.stack[0]
    })
  }

  toHTML() {
    return ""
  }
}

hljs.configure({ __emitter: HastEmitter })
hljs.registerLanguage("json", json)
hljs.registerLanguage("yaml", yaml)

self.onmessage = ({ data: { code, language, blockSize } }) => {
  self.blockSize = blockSize
  hljs.highlight(code, { language }).value
  self.postMessage({
    finished: true
  })
}
