import Alert from "./Alert"
import Log from "./Log"
import fetcher from "./lib/json-fetcher"
import EventBusContext from "./lib/EventBusContext"
import { LOGS_PROCESSCHAINS_PREFIX } from "./lib/EventBusMessages"
import EventBus from "@vertx/eventbus-bridge-client.js"
import { useCallback, useContext, useEffect, useRef, useState } from "react"
import parseRangeHeader from "parse-content-range-header"
import classNames from "classnames"
import styles from "./ProcessChainLog.scss"

const ProcessChainLog = ({ id, onError }) => {
  const nextEntryKey = useRef(0)
  const [lines, setLines] = useState([])
  const [error, doSetError] = useState()
  const [loadingVisible, setLoadingVisible] = useState(false)
  const contentRange = useRef(undefined)
  const eventBus = useContext(EventBusContext)

  const lineToEntry = useCallback((line) => {
    return {
      key: nextEntryKey.current++,
      value: line
    }
  }, [])

  const setError = useCallback((error) => {
    doSetError(error)
    if (onError) {
      onError(error)
    }
  }, [onError])

  const load = useCallback((more = false, callback) => {
    async function handleResponse(r) {
      let body = await r.text()
      if (r.status === 404) {
        return undefined
      } else if (r.status === 200) {
        contentRange.current = parseRangeHeader(r.headers.get("x-content-range"))

        // strip first line (which is most likely incomplete)
        if (contentRange.current.first !== 0) {
          let lineEnd = body.indexOf("\n") + 1
          body = body.substring(lineEnd)
          contentRange.current.first += lineEnd
        }

        // strip last line break - it would lead to an empty line later
        if (body[body.length - 1] === "\n") {
          body = body.substring(0, body.length - 1)
          contentRange.current.last--
        }
      } else if (r.status !== 200) {
        throw new Error(body)
      }
      return body
    }

    if (more && contentRange.current.first === 0) {
      // nothing more to load
      return
    }

    let bytes = -2000000 // load last 2 MB
    if (more) {
      bytes = Math.max(0, contentRange.current.first + bytes) + "-" +
        (contentRange.current.first - 1)
    }

    let options = {
      headers: {
        "accept": "text/plain",
        "x-range": `bytes=${bytes}`
      }
    }

    setLoadingVisible(true)

    fetcher(`${process.env.baseUrl}/logs/processchains/${id}`, false, options, handleResponse)
      .then(log => {
        if (log === undefined) {
          setError(<Alert error>
              <p>Unable to find process chain log</p>
              <p>This can have various reasons:</p>
              <ul>
                <li>The process chain has not produced any output (yet)</li>
                <li>The agent that has executed the process chain is not available anymore</li>
                <li>Process chain logging is disabled in Steep&rsquo;s configuration</li>
              </ul>
            </Alert>)
          setLines(undefined)
        } else {
          setLines(old => [...log.split("\n").map(lineToEntry), ...(old || [])])
        }
      })
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load process chain</Alert>)
        setLines(undefined)
      })
      .finally(() => {
        setLoadingVisible(false)
        if (callback) {
          callback()
        }
      })
  }, [id, lineToEntry, setError])

  useEffect(() => {
    if (id === undefined) {
      return
    }
    load()
  }, [id, load])

  // register event bus consumer receiving live log lines
  useEffect(() => {
    if (id === undefined) {
      return
    }

    function onNewLogLine(err, msg) {
      setLines(old => [...(old || []), lineToEntry(msg.body)])
      setError(undefined)
    }

    let address = LOGS_PROCESSCHAINS_PREFIX + id
    if (eventBus !== undefined) {
      eventBus.registerHandler(address, onNewLogLine)
    }

    return () => {
      if (eventBus !== undefined && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(address, onNewLogLine)
      }
    }
  }, [id, eventBus, lineToEntry, setError])

  let codeVisible = lines !== undefined

  return (<>
    {codeVisible || error}
    <div className={classNames("processchainlog", { visible: codeVisible })}>
      <Log onLoadMore={(callback) => load(true, callback)}>{lines}</Log>
      <div className={classNames("loading", { visible: loadingVisible })}>
        <div className="sk-flow">
          <div className="sk-flow-dot"></div>
          <div className="sk-flow-dot"></div>
          <div className="sk-flow-dot"></div>
        </div>
      </div>
    </div>
    <style jsx>{styles}</style>
  </>)
}

export default ProcessChainLog
