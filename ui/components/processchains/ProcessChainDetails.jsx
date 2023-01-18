import DetailPage from "../layouts/DetailPage"
import Link from "next/link"
import { useContext, useEffect, useState } from "react"
import Alert from "../Alert"
import CancelModal from "../CancelModal"
import CollapseButton from "../CollapseButton"
import CodeBox from "../CodeBox"
import DefinitionList from "../DefinitionList"
import DefinitionListItem from "../DefinitionListItem"
import EventBusContext from "../lib/EventBusContext"
import { LOGS_PROCESSCHAINS_PREFIX } from "../lib/EventBusMessages"
import Label from "../Label"
import ListItemProgressBox from "../ListItemProgressBox"
import LiveDuration from "../LiveDuration"
import Priority from "../Priority"
import ProcessChainContext from "../processchains/ProcessChainContext"
import ProcessChainLog from "../ProcessChainLog"
import RunMenuItem from "../processchains/RunMenuItem"
import Tooltip from "../Tooltip"
import { formatDate, formatDurationTitle } from "../lib/date-time-utils"
import fetcher from "../lib/json-fetcher"
import EventBus from "@vertx/eventbus-bridge-client.js"
import classNames from "classnames"
import styles from "./ProcessChainDetails.scss"

const ProcessChainDetails = ({ id, runNumber = undefined }) => {
  const processChains = useContext(ProcessChainContext.Items)
  const updateProcessChains = useContext(ProcessChainContext.UpdateItems)
  const eventBus = useContext(EventBusContext)
  const [error, setError] = useState()
  const [cancelModalOpen, setCancelModalOpen] = useState()
  const [logAvailable, setLogAvailable] = useState(false)
  const [logCollapsed, setLogCollapsed] = useState()
  const [logError, setLogError] = useState()
  const [waitForLog, setWaitForLog] = useState(false)
  const [runToShow, setRunToShow] = useState(runNumber)

  useEffect(() => {
    if (!id) {
      return
    }

    let promises = []
    let url = `${process.env.baseUrl}/processchains/${id}`
    promises.push(fetcher(url))
    promises.push(fetcher(`${url}/runs`))

    Promise.all(promises)
      .then(([pc, runs]) => {
        let pcs = []
        let i = 1
        let totalRuns = runs.length
        for (let run of runs) {
          // create a copy of the main process chain
          let npc = { ...pc }

          // remove all attributes related to runs
          delete npc.startTime
          delete npc.endTime
          delete npc.status
          delete npc.errorMessage
          delete npc.autoResumeAfter
          delete npc.runNumber
          delete npc.totalRuns

          // add attributes from run
          npc = { ...npc, ...run, runNumber: i, totalRuns }

          pcs.push(npc)

          ++i
        }

        // add artificial 'current' run if necessary
        if (runs.length === 0 || pc.status === "REGISTERED" || pc.status === "PAUSED" ||
          (pc.status === "CANCELLED" && pc.status !== runs[runs.length - 1].status)) {
          let npc = { ...pc }
          npc.totalRuns = runs.length
          pcs.push(npc)
        }

        updateProcessChains({ action: "set", items: pcs })

        if (runNumber === undefined) {
          setRunToShow(pcs.length)
        } else {
          setRunToShow(runNumber)
        }
      })
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load process chain</Alert>)
      })

    // check if a log file is available
    fetcher(`${process.env.baseUrl}/logs/processchains/${id}`, false, {
      method: "HEAD"
    }).then(() => setLogAvailable(true))
      .catch(() => {
        setLogAvailable(false)
        setWaitForLog(true)
      })
  }, [id, updateProcessChains, runNumber])

  useEffect(() => {
    let processChainLogConsumerAddress = LOGS_PROCESSCHAINS_PREFIX + id

    function doWaitForLog() {
      // register a handler that listens to log events, enables the log
      // section, and then unregisters itself
      if (eventBus !== undefined) {
        eventBus.registerHandler(processChainLogConsumerAddress, onNewLogLine)
      }
    }

    function onNewLogLine() {
      setLogAvailable(true)
      unregisterLogConsumer()
    }

    function unregisterLogConsumer() {
      if (eventBus !== undefined && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(processChainLogConsumerAddress, onNewLogLine)
      }
    }

    if (waitForLog) {
      doWaitForLog()
    }

    return () => {
      unregisterLogConsumer()
    }
  }, [id, eventBus, waitForLog])

  function onCancel() {
    setCancelModalOpen(true)
  }

  function onDoCancel() {
    setCancelModalOpen(false)
    fetcher(`${process.env.baseUrl}/processchains/${id}`, false, {
      method: "PUT",
      body: JSON.stringify({
        status: "CANCELLED"
      })
    }).catch(error => {
      console.error(error)
    })
  }

  function onDoChangePriority(priority) {
    fetcher(`${process.env.baseUrl}/processchains/${id}`, false, {
      method: "PUT",
      body: JSON.stringify({
        priority
      })
    }).catch(error => {
      console.error(error)
    })
  }

  let breadcrumbs
  let title
  let processchain
  let menu
  let deleted

  if (processChains.items !== undefined && processChains.items.length >= runToShow) {
    let pc = processChains.items[runToShow - 1]
    title = pc.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows">Workflows</Link>,
      <Link href="/workflows/[id]" as={`/workflows/${pc.submissionId}`} key={pc.submissionId}>
        {pc.submissionId}
      </Link>,
      <Link href={{
        pathname: "/processchains",
        query: {
          submissionId: pc.submissionId
        }
      }} key="processchains">
        Process chains
      </Link>,
      pc.id
    ]

    let menuItems = []
    if (logAvailable) {
      menuItems.push(<a href={`${process.env.baseUrl}/logs/processchains/${id}?forceDownload=true`}
        key="download-log"><li>Download log</li></a>)
    }
    if (pc.status === "REGISTERED" || pc.status === "RUNNING" || pc.status === "PAUSED") {
      menuItems.push(<li key="cancel" onClick={onCancel}>Cancel</li>)
    }
    if (menuItems.length > 0) {
      menu = <ul>{menuItems}</ul>
    }

    deleted = !!pc.deleted

    let reqcap
    if (pc.requiredCapabilities === undefined || pc.requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = pc.requiredCapabilities.flatMap((r, i) => [<Label key={i}>{r}</Label>, <wbr key={`wbr${i}`}/>])
    }

    let estimatedProgress
    if (pc.status === "RUNNING" && pc.estimatedProgress !== undefined && pc.estimatedProgress !== null) {
      estimatedProgress = (
        <Tooltip title="Estimated progress">
          {(pc.estimatedProgress * 100).toFixed()}&thinsp;%
        </Tooltip>
      )
    }

    let progress = {
      status: pc.status,
      subtitle: estimatedProgress
    }

    processchain = (<>
      <div className="detail-header">
        <div className="left-two-columns">
          <div className="detail-header-left">
            <DefinitionList>
              <DefinitionListItem title="Start time">
                {pc.startTime ? formatDate(pc.startTime) : <>&ndash;</>}
              </DefinitionListItem>
              <DefinitionListItem title="End time">
                {pc.endTime ? formatDate(pc.endTime) : <>&ndash;</>}
              </DefinitionListItem>
              <DefinitionListItem title="Time elapsed">
                {
                  pc.startTime && pc.endTime ? formatDurationTitle(pc.startTime, pc.endTime) : (
                    pc.startTime ? <LiveDuration startTime={pc.startTime} /> : <>&ndash;</>
                  )
                }
              </DefinitionListItem>
            </DefinitionList>
          </div>
          <div className="detail-header-middle">
            <DefinitionList>
              <DefinitionListItem title="Priority">
                <Priority value={pc.priority} onChange={v => onDoChangePriority(v)}
                  subjectShort="process chain" subjectLong="process chain"
                  editable={pc.status === "REGISTERED" || pc.status === "RUNNING" ||
                    pc.status === "PAUSED"} />
              </DefinitionListItem>
              <DefinitionListItem title="Required capabilities">
                {reqcap}
              </DefinitionListItem>
            </DefinitionList>
          </div>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} deleted={deleted} />
        </div>
      </div>
      {pc.errorMessage && (<>
        <h2>Error message</h2>
        <Alert error>{pc.errorMessage}</Alert>
      </>)}
      {logAvailable && (<>
        <h2><CollapseButton collapsed={logCollapsed}
          onCollapse={() => setLogCollapsed(!logCollapsed)}>Log</CollapseButton></h2>
        {logCollapsed && (
          <div className={classNames("log-wrapper", { error: logError !== undefined })}>
            <ProcessChainLog id={id} onError={setLogError} />
          </div>
        )}
      </>)}
      <h2>Executables</h2>
      <CodeBox json={pc.executables} />
      <style jsx>{styles}</style>
    </>)
  }

  let runsMenu
  let runsMenuTitle = "Runs"
  if (processChains.items !== undefined &&
      processChains.items.length > 1 &&
      processChains.items.length >= runToShow) {
    if (processChains.items[runToShow - 1].runNumber !== undefined) {
      runsMenuTitle = `Run #${processChains.items[runToShow - 1].runNumber}`
    }

    let menuItems = []
    for (let i = processChains.items.length - 1; i >= 0; --i) {
      let run = processChains.items[i]
      let enabled = runToShow === i + 1
      menuItems.push(<RunMenuItem processChainId={id}
        runNumber={run.runNumber} run={run}
        isLatest={i === processChains.items.length - 1}
        key={`run-${i}`} enabled={enabled} />)
    }

    runsMenu = <ul>{menuItems}</ul>
  }

  let menus = []
  if (runsMenu !== undefined) {
    menus.push({
      title: runsMenuTitle,
      menu: runsMenu
    })
  }
  if (menu !== undefined) {
    menus.push({
      title: "Actions",
      menu
    })
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title} menus={menus} deleted={deleted}>
      {processchain}
      {error}
      <CancelModal isOpen={cancelModalOpen} contentLabel="Cancel modal"
          onRequestClose={() => setCancelModalOpen(false)} title="Cancel process chain"
          onConfirm={onDoCancel} onDeny={() => setCancelModalOpen(false)}>
        <p>Are you sure you want to cancel this process chain?</p>
      </CancelModal>
    </DetailPage>
  )
}

export default ProcessChainDetails
