import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useState } from "react"
import Alert from "../../components/Alert"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import VMContext from "../../components/vms/VMContext"
import vmToProgress from "../../components/vms/vm-to-progress"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"
import "./[id].scss"

function VM({ id }) {
  const vms = useContext(VMContext.State)
  const updateVMs = useContext(VMContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/vms/${id}`)
        .then(vm => updateVMs({ action: "push", vms: [vm] }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load VM</Alert>)
        })
    }
  }, [id, updateVMs])

  let breadcrumbs
  let title
  let result

  if (vms !== undefined && vms.length > 0) {
    let vm = vms[0]
    title = vm.id
    breadcrumbs = [
      <Link href="/vms" key="vms"><a>VMs</a></Link>,
      vm.id
    ]

    let caps
    if (vm.setup.providedCapabilities === undefined ||
        vm.setup.providedCapabilities.length === 0) {
      caps = <>&ndash;</>
    } else {
      caps = vm.setup.providedCapabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }

    let progress = vmToProgress(vm)

    result = (<>
      <div className="detail-header">
        <div className="detail-header-left">
          <DefinitionList>
            <DefinitionListItem title="Creation time">
              {vm.creationTime ? formatDate(vm.creationTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Time when the agent joined the cluster">
              {vm.agentJoinTime ? formatDate(vm.agentJoinTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Destruction time">
              {vm.destructionTime ? formatDate(vm.destructionTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Uptime">
              {
                vm.creationTime && vm.destructionTime ?
                  formatDurationTitle(vm.creationTime, vm.destructionTime) : (
                    vm.creationTime ? <LiveDuration startTime={vm.creationTime} /> : <>&ndash;</>
                  )
              }
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
      <div className="vm-details-two-column">
        <div className="vm-details-left">
          <DefinitionList>
            <DefinitionListItem title="Provided capabilities">
              {caps}
            </DefinitionListItem>
            <DefinitionListItem title="IP address">
              {vm.ipAddress || <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="External ID">
              {vm.externalId || <>&ndash;</>}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="vm-details-right">
          <DefinitionList>
          </DefinitionList>
        </div>
      </div>
      {vm.reason && vm.status === "ERROR" && (<>
        <h2>Error message</h2>
        <Alert error>{vm.reason}</Alert>
      </>)}
      {vm.reason && vm.status !== "ERROR" && (<>
        <h2>Status</h2>
        <Alert info>{vm.reason}</Alert>
      </>)}
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {result}
      {error}
    </DetailPage>
  )
}

export default () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <VMContext.Provider pageSize={1} allowAdd={false}>
      <VM id={id} />
    </VMContext.Provider>
  )
}
