import Alert from "./Alert"
import Code from "./Code"
import Tooltip from "./Tooltip"
import fetcher from "./lib/json-fetcher"
import EventBusContext from "./lib/EventBusContext"
import { LOGS_PROCESSCHAINS_PREFIX } from "./lib/EventBusMessages"
import EventBus from "vertx3-eventbus-client"
import { useCallback, useContext, useEffect, useRef, useState } from "react"
import { throttle } from "lodash"
import { ChevronsDown } from "react-feather"
import classNames from "classnames"
import styles from "./ProcessChainLog.scss"

const ProcessChainLog = ({ id }) => {
  const ref = useRef()
  const [contents, setContents] = useState()
  const [liveContents, setLiveContents] = useState([])
  const [error, setError] = useState()
  const [followButtonVisible, setFollowButtonVisible] = useState(false)
  const eventBus = useContext(EventBusContext)

  const shouldScrollToEnd = useCallback((factor = 2) => {
    let lineHeight = parseInt(window.getComputedStyle(ref.current).lineHeight)
    let threshold = ref.current.scrollHeight - ref.current.clientHeight - lineHeight * factor
    return threshold < ref.current.scrollTop
  }, [])

  const scrollToEnd = useCallback((force = false) => {
    setTimeout(() => {
      if (force || shouldScrollToEnd()) {
        ref.current.scrollTop = ref.current.scrollHeight
      }
    }, 0)
  }, [shouldScrollToEnd])

  const onFollowClick = useCallback(() => {
    scrollToEnd(true)
  }, [scrollToEnd])

  useEffect(() => {
    let codeRef = ref.current

    const onScroll = throttle(() => {
      setFollowButtonVisible(!shouldScrollToEnd(1))
    }, 100)

    if (id === undefined) {
      return
    }

    // fetch process chain log
    let options = {
      headers: {
        "accept": "text/plain"
      }
    }

    async function handleResponse(r) {
      let body = await r.text()
      if (r.status === 404) {
        return undefined
      } else if (r.status !== 200) {
        throw new Error(body)
      }
      return body
    }

    fetcher(`${process.env.baseUrl}/logs/processchains/${id}`, false, options, handleResponse)
        .then(log => {
          if (log === undefined) {
            setError(<Alert error>
              <p>Unable to find process chain logs</p>
              <p>This can have several reasons:</p>
              <ul>
                <li>The process chain has not produced any output (yet)</li>
                <li>The agent that has executed the process chain is not available anymore</li>
                <li>Process chain logging is disabled in Steep&rsquo;s configuration</li>
              </ul>
            </Alert>)
          }
          setContents(log)
          scrollToEnd(true)
          setFollowButtonVisible(false)
        })
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load process chain</Alert>)
        })

    // register event bus consumer receiving live log lines
    let address = LOGS_PROCESSCHAINS_PREFIX + id

    function onNewLogLine(err, msg) {
      setLiveContents(oldContents => [...oldContents, msg.body])
      scrollToEnd()
    }

    if (eventBus !== undefined) {
      eventBus.registerHandler(address, onNewLogLine)
    }

    // register scroll listener to control visibility of "follow" button
    codeRef.addEventListener("scroll", onScroll)

    return () => {
      if (eventBus !== undefined && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(address, onNewLogLine)
      }
      codeRef.removeEventListener("scroll", onScroll)
    }
  }, [id, eventBus, scrollToEnd, shouldScrollToEnd])

  return (<>
    {error}
    <div className={classNames("processchainlog", { visible: contents || liveContents.length > 0 })}>
      <Code lang="log" ref={ref}>{contents && [contents, ...liveContents]}</Code>
      <div className={classNames("follow-button", { visible: followButtonVisible })} onClick={onFollowClick}>
        <Tooltip title="Follow">
          <ChevronsDown className="feather" />
        </Tooltip>
      </div>
    </div>
    <style jsx>{styles}</style>
  </>)
}

export default ProcessChainLog
