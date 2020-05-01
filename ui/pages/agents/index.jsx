import Page from "../../components/layouts/Page"
import Ago from "../../components/Ago"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import AgentContext from "../../components/agents/AgentContext"
import { useContext, useEffect, useMemo, useState } from "react"
import { formatDistanceToNow } from "date-fns"
import agentToProgress from "../../components/agents/agent-to-progress"
import { formatDate } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: false, includeSeconds: true })
}

function AgentListItem({ agent }) {
  return useMemo(() => {
    let href = `/agents/${agent.id}`

    let progress = agentToProgress(agent)

    let upSinceTitle = formatDate(agent.startTime)
    let subtitle = <>Up since <Ago date={agent.startTime}
      formatter={formatterToNow} title={upSinceTitle} /></>

    return <ListItem key={agent.id} justAdded={agent.justAdded}
        justLeft={agent.left} linkHref={href} title={agent.id}
        subtitle={subtitle} progress={progress} labels={agent.capabilities} />
  }, [agent])
}

function AgentList() {
  const agents = useContext(AgentContext.Items)
  const updateAgents = useContext(AgentContext.UpdateItems)
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(`${process.env.baseUrl}/agents`)
      .then(agents => updateAgents({ action: "set", items: agents }))
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load agents</Alert>)
      })
  }, [updateAgents])

  let items
  if (agents.items !== undefined) {
    items = agents.items.map(agent => <AgentListItem key={agent.id} agent={agent} />)
  }

  return (<>
    {items}
    {items && items.length === 0 && <>There are no agents.</>}
    {error}
  </>)
}

export default () => {
  return (
    <Page title="Agents">
      <h1>Agents</h1>
      <AgentContext.Provider>
        <AgentList />
      </AgentContext.Provider>
    </Page>
  )
}
