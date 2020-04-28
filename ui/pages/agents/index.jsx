import Page from "../../components/layouts/Page"
import Ago from "../../components/Ago"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import AgentContext from "../../components/agents/AgentContext"
import { useContext, useEffect, useState } from "react"
import { formatDistanceToNow } from "date-fns"
import agentToProgress from "../../components/agents/agent-to-progress"
import { formatDate } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: false, includeSeconds: true })
}

function onAgentChanged(agent) {
  let href = `/agents/${agent.id}`

  let progress = agentToProgress(agent)

  let upSinceTitle = formatDate(agent.startTime)
  let subtitle = <>Up since <Ago date={agent.startTime}
    formatter={formatterToNow} title={upSinceTitle} /></>

  agent.element = (
    <ListItem key={agent.id} justAdded={agent.justAdded} justLeft={agent.left}
      linkHref={href} title={agent.id} subtitle={subtitle}
      progress={progress} labels={agent.capabilities} />
  )
}

function AgentList() {
  const agents = useContext(AgentContext.State)
  const updateAgents = useContext(AgentContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(`${process.env.baseUrl}/agents`)
      .then(agents => updateAgents({ action: "set", agents }))
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load agents</Alert>)
      })
  }, [updateAgents])

  return (<>
    {agents && agents.map(a => a.element)}
    {agents && agents.length === 0 && <>There are no agents.</>}
    {error}
  </>)
}

export default () => {
  return (
    <Page title="Agents">
      <h1>Agents</h1>
      <AgentContext.Provider onAgentChanged={onAgentChanged}>
        <AgentList />
      </AgentContext.Provider>
    </Page>
  )
}
