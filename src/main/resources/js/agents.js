const AGENT_ADDRESS_PREFIX = "RemoteAgentRegistry.Agent.";

function _sortAgents(agents) {
  agents.sort((a, b) => a.id.localeCompare(b.id));
}

if (window.singleAgent === undefined) {
  window.singleAgent = false;
}

_sortAgents(window.agents);

let app = new Vue({
  el: '#app',
  data: {
    agents: window.agents
  }
});

let eb = new EventBus("/eventbus");
eb.enableReconnect(true);
eb.onopen = () => {
};
