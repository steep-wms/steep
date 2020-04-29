import ListPage from "../../components/layouts/ListPage"
import Ago from "../../components/Ago"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import Pagination from "../../components/Pagination"
import Tooltip from "../../components/Tooltip"
import VMContext from "../../components/vms/VMContext"
import { useContext, useEffect, useState } from "react"
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

function VMList({ pageSize, pageOffset }) {
  const vms = useContext(VMContext.State)
  const updateVMs = useContext(VMContext.Dispatch)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  useEffect(() => {
    let params = new URLSearchParams()
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)

    fetcher(`${process.env.baseUrl}/vms?${params.toString()}`, true)
      .then(r => {
        let vms = r.body
        updateVMs({ action: "set", vms })
        let pageTotalHeader = r.headers.get("x-page-total")
        if (pageTotalHeader !== null) {
          setPageTotal(+pageTotalHeader)
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load VMs</Alert>)
      })
  }, [pageOffset, pageSize, updateVMs])

  function reset(newOffset) {
    if (newOffset !== pageOffset) {
      updateVMs({ action: "set", vms: undefined })
      setPageTotal(0)
    }
  }

  return (<>
    {vms && vms.map(w => w.element)}
    {vms && vms.length === 0 && <>There are no VMs.</>}
    {error}
    {pageTotal > 0 && (
      <Pagination pageSize={pageSize} pageOffset={pageOffset} pageTotal={pageTotal}
        onChangeOffset={reset} />
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

  return (
    <ListPage title="VMs">
      <h1>VMs</h1>
      <VMContext.Provider pageSize={pageSize} onVMChanged={onVMChanged}>
        <VMList pageSize={pageSize} pageOffset={pageOffset} />
      </VMContext.Provider>
    </ListPage>
  )
}
