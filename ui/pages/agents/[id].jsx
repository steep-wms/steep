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
import AgentContext from "../../components/agents/AgentContext"
import styles from "./[id].scss"
import { formatDate } from "../../components/lib/date-time-utils"
import agentToProgress from "../../components/agents/agent-to-progress"
import fetcher from "../../components/lib/json-fetcher"

function AgentDetails({ id }) {
  const agents = useContext(AgentContext.Items)
  const updateAgents = useContext(AgentContext.UpdateItems)
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/agents/${id}`)
        .then(agent => updateAgents({ action: "set", items: [agent] }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load agent</Alert>)
        })
    }
  }, [id, updateAgents])

  let breadcrumbs
  let title
  let agent

  if (agents.items !== undefined && agents.items.length > 0) {
    let a = agents.items[0]
    title = a.id
    breadcrumbs = [
      <Link href="/agents" key="agents">Agents</Link>,
      a.id
    ]

    let caps
    if (a.capabilities === undefined || a.capabilities.length === 0) {
      caps = <>&ndash;</>
    } else {
      caps = a.capabilities.map((r, i) => <><Label key={i}>{r}</Label><wbr/></>)
    }

    let progress = agentToProgress(a)

    agent = (<>
      <div className="detail-header">
        <div className="detail-header-left">
          <DefinitionList>
            <DefinitionListItem title="Start time">
              {a.startTime ? formatDate(a.startTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Uptime">
              {a.startTime && !a.left ? <LiveDuration startTime={a.startTime} /> : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Capabilities">
              {caps}
            </DefinitionListItem>
            <DefinitionListItem title="Allocated process chain">
              {a.processChainId ? <Link href={`/processchains/${a.processChainId}`}>
                {a.processChainId}</Link> : <>&ndash;</>}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
      {a.left && (<>
        <div className="agent-detail-left">
          <Alert error>Agent has left the cluster</Alert>
        </div>
      </>)}
      <style jsx>{styles}</style>
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {agent}
      {error}
    </DetailPage>
  )
}

const Agent = () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <AgentContext.Provider pageSize={1} allowAdd={false}>
      <AgentDetails id={id} />
      <style jsx>{styles}</style>
    </AgentContext.Provider>
  )
}

export default Agent
