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
import { deleteRunInformation } from "./processChainUtil"
import ProcessChainContext from "../processchains/ProcessChainContext"
import ProcessChainRunContext from "../processchains/ProcessChainRunContext"
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
  const runs = useContext(ProcessChainRunContext.Items)
  const updateProcessChains = useContext(ProcessChainContext.UpdateItems)
  const updateProcessChainRuns = useContext(ProcessChainRunContext.UpdateItems)
  const eventBus = useContext(EventBusContext)
  const [error, setError] = useState()
  const [cancelModalOpen, setCancelModalOpen] = useState()
  const [logAvailable, setLogAvailable] = useState(false)
  const [logCollapsed, setLogCollapsed] = useState()
  const [logError, setLogError] = useState()
  const [waitForLog, setWaitForLog] = useState(false)

  let processChain
  if (processChains.items !== undefined && processChains.items.length > 0) {
    if (runNumber === undefined) {
      processChain = processChains.items[0]
    } else {
      if (runs.items === undefined) {
        processChain = undefined
      } else if (runs.items.length === 0) {
        processChain = processChains.items[0]
      } else {
        processChain = { ...processChains.items[0] }

        // remove all attributes related to runs
        deleteRunInformation(processChain)

        // add attributes from run
        let run = runs.items[runs.items.length - runNumber]
        processChain = {
          ...processChain,
          startTime: run.startTime,
          endTime: run.endTime,
          status: run.status,
          errorMessage: run.errorMessage,
          autoResumeAfter: run.autoResumeAfter,
          runNumber
        }
      }
    }
  } else {
    processChain = undefined
  }

  useEffect(() => {
    if (!id) {
      return
    }

    let url = `${process.env.baseUrl}/processchains/${id}`

    fetcher(url)
      .then(pc => updateProcessChains({ action: "set", items: [pc] }))
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load process chain</Alert>)
      })

    fetcher(`${url}/runs`)
      .then(runs => updateProcessChainRuns({ action: "set", items: runs }))
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load process chain runs</Alert>)
      })

    // check if a log file is available
    fetcher(`${process.env.baseUrl}/logs/processchains/${id}`, false, {
      method: "HEAD"
    })
      .then(() => setLogAvailable(true))
      .catch(() => {
        setLogAvailable(false)
        setWaitForLog(true)
      })
  }, [id, updateProcessChains, updateProcessChainRuns, runNumber])

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

  if (processChain !== undefined) {
    title = processChain.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows">
        Workflows
      </Link>,
      <Link
        href="/workflows/[id]"
        as={`/workflows/${processChain.submissionId}`}
        key={processChain.submissionId}
      >
        {processChain.submissionId}
      </Link>,
      <Link
        href={{
          pathname: "/processchains",
          query: {
            submissionId: processChain.submissionId
          }
        }}
        key="processchains"
      >
        Process chains
      </Link>,
      processChain.id
    ]

    let menuItems = []
    if (logAvailable) {
      menuItems.push(
        <a
          href={`${process.env.baseUrl}/logs/processchains/${id}?forceDownload=true`}
          key="download-log"
        >
          <li>Download log</li>
        </a>
      )
    }
    if (
      processChain.status === "REGISTERED" ||
      processChain.status === "RUNNING" ||
      processChain.status === "PAUSED"
    ) {
      menuItems.push(
        <li key="cancel" onClick={onCancel}>
          Cancel
        </li>
      )
    }
    if (menuItems.length > 0) {
      menu = <ul>{menuItems}</ul>
    }

    deleted = !!processChain.deleted

    let reqcap
    if (
      processChain.requiredCapabilities === undefined ||
      processChain.requiredCapabilities.length === 0
    ) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = processChain.requiredCapabilities.flatMap((r, i) => [
        <Label key={i}>{r}</Label>,
        <wbr key={`wbr${i}`} />
      ])
    }

    let estimatedProgress
    if (
      processChain.status === "RUNNING" &&
      processChain.estimatedProgress !== undefined &&
      processChain.estimatedProgress !== null
    ) {
      estimatedProgress = (
        <Tooltip title="Estimated progress">
          {(processChain.estimatedProgress * 100).toFixed()}&thinsp;%
        </Tooltip>
      )
    }

    let progress = {
      status: processChain.status,
      subtitle: estimatedProgress
    }

    processchain = (
      <>
        <div className="detail-header">
          <div className="left-two-columns">
            <div className="detail-header-left">
              <DefinitionList>
                <DefinitionListItem title="Start time">
                  {processChain.startTime ? (
                    formatDate(processChain.startTime)
                  ) : (
                    <>&ndash;</>
                  )}
                </DefinitionListItem>
                <DefinitionListItem title="End time">
                  {processChain.endTime ? (
                    formatDate(processChain.endTime)
                  ) : (
                    <>&ndash;</>
                  )}
                </DefinitionListItem>
                <DefinitionListItem title="Time elapsed">
                  {processChain.startTime && processChain.endTime ? (
                    formatDurationTitle(
                      processChain.startTime,
                      processChain.endTime
                    )
                  ) : processChain.startTime ? (
                    <LiveDuration startTime={processChain.startTime} />
                  ) : (
                    <>&ndash;</>
                  )}
                </DefinitionListItem>
              </DefinitionList>
            </div>
            <div className="detail-header-middle">
              <DefinitionList>
                <DefinitionListItem title="Priority">
                  <Priority
                    value={processChain.priority}
                    onChange={v => onDoChangePriority(v)}
                    subjectShort="process chain"
                    subjectLong="process chain"
                    editable={
                      processChain.status === "REGISTERED" ||
                      processChain.status === "RUNNING" ||
                      processChain.status === "PAUSED"
                    }
                  />
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
        {processChain.errorMessage && (
          <>
            <h2>Error message</h2>
            <Alert error>{processChain.errorMessage}</Alert>
          </>
        )}
        {logAvailable && (
          <>
            <h2>
              <CollapseButton
                collapsed={logCollapsed}
                onCollapse={() => setLogCollapsed(!logCollapsed)}
              >
                Log
              </CollapseButton>
            </h2>
            {logCollapsed && (
              <div
                className={classNames("log-wrapper", {
                  error: logError !== undefined
                })}
              >
                <ProcessChainLog id={id} onError={setLogError} />
              </div>
            )}
          </>
        )}
        <h2>Executables</h2>
        <CodeBox json={processChain.executables} />
        <style jsx>{styles}</style>
      </>
    )
  }

  let runsMenu
  let runsMenuTitle = "Runs"
  if (runs.items !== undefined && processChain !== undefined) {
    let runsToDisplay = [...runs.items]
    runsToDisplay.reverse()
    // add artificial 'current' run if necessary
    let originalProcessChain = processChains.items[0]
    let artificial
    if (
      runs.items.length === 0 ||
      originalProcessChain.status === "REGISTERED" ||
      originalProcessChain.status === "PAUSED" ||
      (originalProcessChain.status === "CANCELLED" &&
        originalProcessChain.status !== runs.items[0].status)
    ) {
      runsToDisplay.push(originalProcessChain)
      artificial = originalProcessChain
    }

    if (runsToDisplay.length > 1) {
      let currentRun = runNumber ?? runsToDisplay.length
      if (runsToDisplay[currentRun - 1].runNumber !== undefined) {
        runsMenuTitle = `Run #${runsToDisplay[currentRun - 1].runNumber}`
      }

      let menuItems = []
      for (let i = runsToDisplay.length - 1; i >= 0; --i) {
        let run = runsToDisplay[i]
        let n = run === artificial ? run.runNumber : i + 1
        let enabled =
          (runNumber === undefined && run === artificial) || currentRun === n
        menuItems.push(
          <RunMenuItem
            processChainId={id}
            runNumber={n}
            run={run}
            isLatest={i === runsToDisplay.length - 1}
            key={`run-${i}`}
            enabled={enabled}
          />
        )
      }

      runsMenu = <ul>{menuItems}</ul>
    }
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
    <DetailPage
      breadcrumbs={breadcrumbs}
      title={title}
      menus={menus}
      deleted={deleted}
    >
      {processchain}
      {error}
      <CancelModal
        isOpen={cancelModalOpen}
        contentLabel="Cancel modal"
        onRequestClose={() => setCancelModalOpen(false)}
        title="Cancel process chain"
        onConfirm={onDoCancel}
        onDeny={() => setCancelModalOpen(false)}
      >
        <p>Are you sure you want to cancel this process chain?</p>
      </CancelModal>
    </DetailPage>
  )
}

export default ProcessChainDetails
