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
import useSWRImmutable from "swr/immutable"
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
  const totalRuns = processChains.items?.[0]?.totalRuns
  const { data: runs } = useSWRImmutable(
    () => totalRuns !== undefined ?
      [
        `${process.env.baseUrl}/processchains/${id}/runs`,
        totalRuns,
        processChains.items?.[0]?.status,
        processChains.items?.[1]?.status
      ] : null,
    ([url]) => fetcher(url)
  )

  useEffect(() => {
    if (!id) {
      return
    }

    let url = `${process.env.baseUrl}/processchains/${id}`

    // if runNumber equals undefined, processChains.items[0] contains information
    // about the process chain. If runNumber is defined, processChains.items[0]
    // contains information about the respective run and processChains.items[1]
    // contains information about the process chain itself.
    let promises = []
    if (runNumber !== undefined) {
      promises.push(fetcher(url + `/runs/${runNumber}`))
    }
    promises.push(fetcher(url))

    Promise.all(promises)
      .then(pcs => updateProcessChains({ action: "set", items: pcs }))
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

  if (processChains.items !== undefined && processChains.items.length > 0) {
    let pc = processChains.items[0]
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
  if (
    processChains.items !== undefined &&
    processChains.items.length > 0 &&
    runs !== undefined &&
    runs.length > 0 &&
    !(
      totalRuns === 1 &&
      runNumber === undefined &&
      (processChains.items[0].status === "RUNNING" ||
      processChains.items[0].status === runs[0].status)
    )
  ) {
    let actualRunNumber = processChains.items[0].runNumber
    if (actualRunNumber !== undefined) {
      runsMenuTitle = `Run #${actualRunNumber}`
    }

    let latestRunNumber = processChains.items.length > 1 ?
      processChains.items[1].runNumber : actualRunNumber

    let menuItems = []
    let latestRun
    for (let i = runs.length; i > 0; --i) {
      let run = runs[i - 1]
      let isLatest = i === latestRunNumber
      if (isLatest) {
        latestRun = run
      }
      let enabled = (runNumber === undefined && isLatest) || (runNumber === i)
      menuItems.push(<RunMenuItem processChainId={id} runNumber={i} run={run}
        isLatest={isLatest} key={`run-${i}`} enabled={enabled} />)
    }

    if (latestRun === undefined) {
      menuItems.unshift(<RunMenuItem processChainId={id} key="run-0"
        run={processChains.items[1] ?? processChains.items[0]}
        enabled={runNumber === undefined} />)
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
