import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import AgentContext from "../../components/agents/AgentContext"
import { useContext, useEffect, useState } from "react"
import { formatDistanceToNow } from "date-fns"
import TimeAgo from "react-timeago"
import { formatDate } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: false, includeSeconds: true })
}

function onAgentChanged(agent) {
  let href = `/agents/${agent.id}`

  let progress = {
    status: agent.available ? "IDLE" : "RUNNING"
  }

  if (!agent.available) {
    progress.title = "Busy"
  }

  let changedTitle = formatDate(agent.stateChangedTime)
  progress.subtitle = <TimeAgo date={agent.stateChangedTime}
    formatter={formatterToNow} title={changedTitle} />

  let upSinceTitle = formatDate(agent.startTime)
  let subtitle = <>Up since <TimeAgo date={agent.startTime}
    formatter={formatterToNow} title={upSinceTitle} /></>

  agent.element = (
    <ListItem key={agent.id} justAdded={agent.justAdded} justLeft={agent.left}
      linkHref={href} title={agent.id} subtitle={subtitle}
      progress={progress} />
  )
}

function AgentList() {
  const agents = useContext(AgentContext.State)
  const updateAgents = useContext(AgentContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(`${process.env.baseUrl}/agents`)
      .then(agents => updateAgents({ action: "push", agents }))
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load agents</Alert>)
      })
  }, [updateAgents])

  return (<>
    {agents && agents.map(a => a.element)}
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
