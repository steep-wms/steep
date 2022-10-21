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
import { formatDate, formatDurationTitle, formatDurationMilliseconds } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"
import styles from "./[id].scss"

function VMDetails({ id }) {
  const vms = useContext(VMContext.Items)
  const updateVMs = useContext(VMContext.UpdateItems)
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/vms/${id}`)
        .then(vm => updateVMs({ action: "set", items: [vm] }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load VM</Alert>)
        })
    }
  }, [id, updateVMs])

  let breadcrumbs
  let title
  let result
  let deleted

  if (vms.items !== undefined && vms.items.length > 0) {
    let vm = vms.items[0]
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
      caps = vm.setup.providedCapabilities.map((r, i) => <><Label key={i}>{r}</Label><wbr/></>)
    }

    let progress = vmToProgress(vm)

    deleted = !!vm.deleted

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
          <ListItemProgressBox progress={progress} deleted={deleted} />
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
      <h2>Details</h2>
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
            <DefinitionListItem title="Flavor">
              <span className="vm-details-value">{vm.setup.flavor || <>&ndash;</>}</span>
            </DefinitionListItem>
            <DefinitionListItem title="Image">
              <span className="vm-details-value">{vm.setup.imageName || <>&ndash;</>}</span>
            </DefinitionListItem>
            <DefinitionListItem title="Availability zone">
              <span className="vm-details-value">{vm.setup.availabilityZone || <>&ndash;</>}</span>
            </DefinitionListItem>
          </DefinitionList>
        </div>
      </div>
      <h2>Setup</h2>
      <div className="vm-details-two-column">
        <div className="vm-details-left">
          <DefinitionList>
            <DefinitionListItem title="ID">
              {vm.setup.id}
            </DefinitionListItem>
            <DefinitionListItem title="Block device size">
              {(vm.setup.blockDeviceSizeGb && `${vm.setup.blockDeviceSizeGb} GB`) || <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Block device volume type">
              {vm.setup.blockDeviceVolumeType || "(auto)"}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="vm-details-right">
          <DefinitionList>
            <DefinitionListItem title="Minimum">
              {(vm.setup.minVMs && `${vm.setup.minVMs} instances`) || <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Maximum">
              {(vm.setup.maxVMs && `${vm.setup.maxVMs} instances`) || <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Create concurrently">
              {(vm.setup.maxCreateConcurrent && `max. ${vm.setup.maxCreateConcurrent} instances`) || <>&ndash;</>}
            </DefinitionListItem>
          </DefinitionList>
        </div>
      </div>
      <div className="vm-details-two-column">
        <div className="vm-details-left">
          <DefinitionList>
            <DefinitionListItem title="Additional volumes">
              {(vm.setup.additionalVolumes === undefined || vm.setup.additionalVolumes.length === 0) && <>&ndash;</>}
              {(vm.setup.additionalVolumes !== undefined && vm.setup.additionalVolumes.length > 0) && (
                <div className="vm-details-volume-grid">
                  {vm.setup.additionalVolumes.map(volume => <>
                    <div className="vm-details-volume-size">{volume.sizeGb} GB</div>
                    <div className="vm-details-volume-type">{volume.type || "(auto)"}</div>
                    <div className="vm-details-volume-availability-zone">{volume.availabilityZone ||
                      vm.setup.availabilityZone || <>&ndash;</>}</div>
                  </>)}
                </div>
              )}
            </DefinitionListItem>
          </DefinitionList>
        </div>
      </div>
      <h3>Creation</h3>
      <div className="vm-details-two-column">
        <div className="vm-details-left">
          <DefinitionList>
            <DefinitionListItem title="Maximum number of attempts">
              {vm.setup.creation?.retries?.maxAttempts !== undefined ?
                vm.setup.creation.retries.maxAttempts : <>(default)</>}
            </DefinitionListItem>
            <DefinitionListItem title="Delay between attempts">
              {vm.setup.creation?.retries?.delay !== undefined ?
                formatDurationMilliseconds(vm.setup.creation.retries.delay, true) : <>(default)</>}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="vm-details-left">
          <DefinitionList>
            <DefinitionListItem title="Exponential backoff factor">
              {vm.setup.creation?.retries?.exponentialBackoff !== undefined ?
                vm.setup.creation.retries.exponentialBackoff : <>(default)</>}
            </DefinitionListItem>
            <DefinitionListItem title="Maximum delay between attempts">
              {vm.setup.creation?.retries?.maxDelay !== undefined ?
                formatDurationMilliseconds(vm.setup.creation.retries.maxDelay, true) : <>(default)</>}
            </DefinitionListItem>
          </DefinitionList>
        </div>
      </div>
      <div className="vm-details-two-column">
        <div className="vm-details-left">
          <DefinitionList>
            <DefinitionListItem title="Lock time after all retries">
              {vm.setup.creation?.lockAfterRetries !== undefined ?
                formatDurationMilliseconds(vm.setup.creation.lockAfterRetries, true) : <>(default)</>}
            </DefinitionListItem>
          </DefinitionList>
        </div>
      </div>
      <style jsx>{styles}</style>
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title} deleted={deleted}>
      {result}
      {error}
    </DetailPage>
  )
}

const VM = () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <VMContext.Provider pageSize={1} allowAdd={false}>
      <VMDetails id={id} />
      <style jsx>{styles}</style>
    </VMContext.Provider>
  )
}

export default VM
