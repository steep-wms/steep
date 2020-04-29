import ListPage from "../../components/layouts/ListPage"
import Ago from "../../components/Ago"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import Notification from "../../components/Notification"
import Pagination from "../../components/Pagination"
import Tooltip from "../../components/Tooltip"
import VMContext from "../../components/vms/VMContext"
import { useCallback, useContext, useEffect, useState } from "react"
import vmToProgress from "../../components/vms/vm-to-progress"
import { formatDistanceToNow } from "date-fns"
import { formatDate, formatDuration, formatDurationTitle } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

function formatterToNow(addSuffix) {
  return (value, unit, suffix, epochSeconds) =>
    formatDistanceToNow(epochSeconds, { addSuffix, includeSeconds: true })
}

function onVMChanged(vm) {
  let href = "/vms/[id]"
  let as = `/vms/${vm.id}`

  let progress = vmToProgress(vm)

  let subtitle
  if (vm.creationTime && vm.destructionTime) {
    let agoEndTitle = formatDate(vm.destructionTime)
    let duration = formatDuration(vm.creationTime, vm.destructionTime)
    let durationTitle = formatDurationTitle(vm.creationTime, vm.destructionTime)
    subtitle = (<>
      Destroyed <Ago date={vm.destructionTime}
        formatter={formatterToNow(true)} title={agoEndTitle} /> (was
        up for <Tooltip title={durationTitle}>{duration}</Tooltip>)
    </>)
  } else if (vm.creationTime) {
    let agoTitle = formatDate(vm.creationTime)
    subtitle = <>Up since <Ago date={vm.creationTime}
      formatter={formatterToNow(false)} title={agoTitle} /></>
  }

  vm.element = (
    <ListItem key={vm.id} justAdded={vm.justAdded}
      linkHref={href} linkAs={as} title={vm.id} subtitle={subtitle}
      startTime={vm.creationTime} endTime={vm.destructionTime} progress={progress}
      labels={vm.setup.providedCapabilities} />
  )
}

function VMList({ pageSize, pageOffset, forceUpdate }) {
  const vms = useContext(VMContext.VMs)
  const updateVMs = useContext(VMContext.UpdateVMs)
  const addedVMs = useContext(VMContext.AddedVMs)
  const updateAddedVMs = useContext(VMContext.UpdateAddedVMs)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  const forceReset = useCallback(() => {
    updateVMs({ action: "set", vms: undefined })
    setPageTotal(0)
    updateAddedVMs({ action: "set", n: 0 })
  }, [updateVMs, updateAddedVMs])

  useEffect(() => {
    let params = new URLSearchParams()
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)

    forceReset()

    fetcher(`${process.env.baseUrl}/vms?${params.toString()}`, true)
      .then(r => {
        let vms = r.body
        updateVMs({ action: "set", vms })
        let pageTotalHeader = r.headers.get("x-page-total")
        if (pageTotalHeader !== null) {
          setPageTotal(+pageTotalHeader)
          updateAddedVMs({ action: "set", n: 0 })
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load VMs</Alert>)
      })
  }, [pageOffset, pageSize, updateVMs, updateAddedVMs, forceUpdate, forceReset])

  function reset(newOffset) {
    if (newOffset !== pageOffset) {
      forceReset()
    }
  }

  return (<>
    {vms && vms.map(w => w.element)}
    {vms && vms.length === 0 && <>There are no VMs.</>}
    {error}
    {pageTotal + addedVMs > 0 && (
      <Pagination pageSize={pageSize} pageOffset={pageOffset}
        pageTotal={pageTotal + addedVMs} onChangeOffset={reset} />
    )}
  </>)
}

export default () => {
  // parse query params but do not use "next/router" because router.query
  // is empty on initial render
  let pageOffset
  let pageSize
  if (typeof window !== "undefined") {
    let params = new URLSearchParams(window.location.search)
    pageOffset = params.get("offset") || undefined
    if (pageOffset !== undefined) {
      pageOffset = Math.max(0, parseInt(pageOffset))
    }
    pageSize = params.get("size") || 10
    if (pageSize !== undefined) {
      pageSize = Math.max(0, parseInt(pageSize))
    }
  }

  const [updatesAvailable, setUpdatesAvailable] = useState(false)
  const [forceUpdate, setForceUpdate] = useState(0)

  useEffect(() => {
    setUpdatesAvailable(false)
  }, [pageOffset, pageSize, forceUpdate])

  const addFilter = useCallback(() => {
    if (pageOffset > 0) {
      setUpdatesAvailable(true)
      return false
    }
    return true
  }, [pageOffset])

  return (
    <ListPage title="VMs">
      <h1>VMs</h1>
      <VMContext.Provider pageSize={pageSize} addFilter={addFilter}
          onVMChanged={onVMChanged}>
        <VMList pageSize={pageSize} pageOffset={pageOffset} forceUpdate={forceUpdate} />
      </VMContext.Provider>
      {updatesAvailable && (<Notification>
        New VMs available. <a href="#" onClick={() =>
          setForceUpdate(forceUpdate + 1)}>Refresh</a>.
      </Notification>)}
    </ListPage>
  )
}
