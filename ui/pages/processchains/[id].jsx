import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useState } from "react"
import Alert from "../../components/Alert"
import CodeBox from "../../components/CodeBox"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

function ProcessChain({ id }) {
  const processChains = useContext(ProcessChainContext.State)
  const updateProcessChains = useContext(ProcessChainContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/processchains/${id}`)
        .then(pc => updateProcessChains({ action: "push", processChains: [pc] }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load process chain</Alert>)
        })
    }
  }, [id, updateProcessChains])

  let breadcrumbs
  let title
  let processchain

  if (typeof processChains !== "undefined" && processChains.length > 0) {
    let pc = processChains[0]
    title = pc.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
      <Link href="/workflows/[id]" as={`/workflows/${pc.submissionId}`} key={pc.submissionId}>
        <a>{pc.submissionId}</a>
      </Link>,
      <Link href="/processchains" as={`/processchains/?submissionId=${pc.submissionId}`} key="processchains">
        <a>Process chains</a>
      </Link>,
      pc.id
    ]

    let reqcap
    if (typeof pc.requiredCapabilities === "undefined" || pc.requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = pc.requiredCapabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }

    let progress = {
      status: pc.status
    }

    processchain = (<>
      <div className="detail-header">
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
            <DefinitionListItem title="Required capabilities">
              {reqcap}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
      {pc.errorMessage && (<>
        <h2>Error message</h2>
        <Alert error>{pc.errorMessage}</Alert>
      </>)}
      <h2>Executables</h2>
      <CodeBox json={pc.executables} />
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {processchain}
      {error}
    </DetailPage>
  )
}

export default () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <ProcessChainContext.Provider pageSize={1} allowAdd={false}>
      <ProcessChain id={id} />
    </ProcessChainContext.Provider>
  )
}
