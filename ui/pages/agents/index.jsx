import ListPage from "../../components/layouts/ListPage"
import Ago from "../../components/Ago"
import ListItem from "../../components/ListItem"
import AgentContext from "../../components/agents/AgentContext"
import { useMemo } from "react"
import { formatDistanceToNow } from "date-fns"
import agentToProgress from "../../components/agents/agent-to-progress"
import { formatDate } from "../../components/lib/date-time-utils"

function formatterToNow(value, unit, suffix, epochSeconds) {
  return formatDistanceToNow(epochSeconds, { addSuffix: false, includeSeconds: true })
}

function AgentListItem({ item: agent }) {
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

const Agents = () => (
  <ListPage title="Agents" Context={AgentContext}
      ListItem={AgentListItem} subjects="agents" path="agents" pagination={false} />
)

export default Agents
