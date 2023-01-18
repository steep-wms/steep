import ListPage from "../../components/layouts/ListPage"
import Ago from "../../components/Ago"
import ListItem from "../../components/ListItem"
import Tooltip from "../../components/Tooltip"
import VMContext from "../../components/vms/VMContext"
import { useMemo } from "react"
import vmToProgress from "../../components/vms/vm-to-progress"
import { formatDistanceToNow } from "date-fns"
import {
  formatDate,
  formatDuration,
  formatDurationTitle
} from "../../components/lib/date-time-utils"

const FILTERS = [
  {
    name: "status",
    title: "Failed VMs only",
    enabledValue: "ERROR"
  },
  {
    name: "status",
    title: "Running VMs only",
    enabledValue: "RUNNING"
  }
]

function formatterToNow(addSuffix) {
  return (value, unit, suffix, epochSeconds) =>
    formatDistanceToNow(epochSeconds, { addSuffix, includeSeconds: true })
}

function VMListItem({ item: vm }) {
  return useMemo(() => {
    let href = "/vms/[id]"
    let as = `/vms/${vm.id}`

    let progress = vmToProgress(vm)

    let subtitle
    if (vm.creationTime && vm.destructionTime) {
      let agoEndTitle = formatDate(vm.destructionTime)
      let duration = formatDuration(vm.creationTime, vm.destructionTime)
      let durationTitle = formatDurationTitle(
        vm.creationTime,
        vm.destructionTime
      )
      subtitle = (
        <>
          Destroyed{" "}
          <Ago
            date={vm.destructionTime}
            formatter={formatterToNow(true)}
            title={agoEndTitle}
          />{" "}
          (was up for <Tooltip title={durationTitle}>{duration}</Tooltip>)
        </>
      )
    } else if (vm.creationTime) {
      let agoTitle = formatDate(vm.creationTime)
      subtitle = (
        <>
          Up since{" "}
          <Ago
            date={vm.creationTime}
            formatter={formatterToNow(false)}
            title={agoTitle}
          />
        </>
      )
    }

    return (
      <ListItem
        key={vm.id}
        justAdded={vm.justAdded}
        deleted={vm.deleted}
        linkHref={href}
        linkAs={as}
        title={vm.id}
        subtitle={subtitle}
        startTime={vm.creationTime}
        endTime={vm.destructionTime}
        progress={progress}
        labels={vm.setup.providedCapabilities}
      />
    )
  }, [vm])
}

const VMs = () => (
  <ListPage
    title="VMs"
    Context={VMContext}
    ListItem={VMListItem}
    subjects="VMs"
    path="vms"
    filters={FILTERS}
  />
)

export default VMs
