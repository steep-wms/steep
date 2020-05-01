import ListPage from "../../components/layouts/ListPage"
import Ago from "../../components/Ago"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import Notification from "../../components/Notification"
import Pagination from "../../components/Pagination"
import Tooltip from "../../components/Tooltip"
import VMContext from "../../components/vms/VMContext"
import { useCallback, useContext, useEffect, useMemo, useState } from "react"
import vmToProgress from "../../components/vms/vm-to-progress"
import { formatDistanceToNow } from "date-fns"
import { formatDate, formatDuration, formatDurationTitle } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

function formatterToNow(addSuffix) {
  return (value, unit, suffix, epochSeconds) =>
    formatDistanceToNow(epochSeconds, { addSuffix, includeSeconds: true })
}

function VMListItem({ vm }) {
  return useMemo(() => {
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

    return <ListItem key={vm.id} justAdded={vm.justAdded}
        linkHref={href} linkAs={as} title={vm.id} subtitle={subtitle}
        startTime={vm.creationTime} endTime={vm.destructionTime} progress={progress}
        labels={vm.setup.providedCapabilities} />
  }, [vm])
}

function VMList({ pageSize, pageOffset, forceUpdate }) {
  const vms = useContext(VMContext.Items)
  const updateVMs = useContext(VMContext.UpdateItems)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  const forceReset = useCallback(() => {
    updateVMs({ action: "set" })
    setPageTotal(0)
  }, [updateVMs])

  useEffect(() => {
    let params = new URLSearchParams()
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)

    forceReset()

    fetcher(`${process.env.baseUrl}/vms?${params.toString()}`, true)
      .then(r => {
        updateVMs({ action: "set", items: r.body })
        let pageTotalHeader = r.headers.get("x-page-total")
        if (pageTotalHeader !== null) {
          setPageTotal(+pageTotalHeader)
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load VMs</Alert>)
      })
  }, [pageOffset, pageSize, updateVMs, forceUpdate, forceReset])

  function reset(newOffset) {
    if (newOffset !== pageOffset) {
      forceReset()
    }
  }

  let items
  if (vms.items !== undefined) {
    items = vms.items.map(vm => <VMListItem key={vm.id} vm={vm} />)
  }

  return (<>
    {items}
    {items && items.length === 0 && <>There are no VMs.</>}
    {error}
    {pageTotal + vms.added > 0 && (
      <Pagination pageSize={pageSize} pageOffset={pageOffset}
        pageTotal={pageTotal + vms.added} onChangeOffset={reset} />
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

  function addFilter() {
    if (pageOffset > 0) {
      setTimeout(() => setUpdatesAvailable(true), 0)
      return false
    }
    return true
  }

  return (
    <ListPage title="VMs">
      <h1>VMs</h1>
      <VMContext.Provider pageSize={pageSize} addFilter={addFilter}>
        <VMList pageSize={pageSize} pageOffset={pageOffset} forceUpdate={forceUpdate} />
      </VMContext.Provider>
      {updatesAvailable && (<Notification>
        New VMs available. <a href="#" onClick={() =>
          setForceUpdate(forceUpdate + 1)}>Refresh</a>.
      </Notification>)}
    </ListPage>
  )
}
