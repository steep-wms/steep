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
  },
  methods: {
    sortAgents: function () {
      _sortAgents(this.agents);
    },

    findAgentById: function (id) {
      return this.agents.findIndex(e => e.id === id);
    }
  }
});

let eb = new EventBus("/eventbus");
eb.enableReconnect(true);
eb.onopen = () => {
  eb.registerHandler("jobmanager.remoteAgentRegistry.agentAdded", (error, message) => {
    if (!window.singleAgent) {
      let id = message.body.substring(AGENT_ADDRESS_PREFIX.length);
      fetch("/agents/" + id)
        .then(response => response.json())
        .then(agent => {
          let i = app.findAgentById(agent.id);
          if (i === -1) {
            app.agents.push(agent);
            app.sortAgents();
          }
        })
        .catch(error => console.error(error));
    }
  });

  eb.registerHandler("jobmanager.remoteAgentRegistry.agentLeft", (error, message) => {
    let id = message.body.substring(AGENT_ADDRESS_PREFIX.length);
    let i = app.findAgentById(id);
    if (i !== -1) {
      app.agents.splice(i, 1);
    }
  });

  eb.registerHandler("jobmanager.remoteAgentRegistry.agentBusy", (error, message) => {
    let id = message.body.substring(AGENT_ADDRESS_PREFIX.length);
    let i = app.findAgentById(id);
    if (i !== -1) {
      app.agents[i].available = false;
    }
  });

  eb.registerHandler("jobmanager.remoteAgentRegistry.agentIdle", (error, message) => {
    let id = message.body.substring(AGENT_ADDRESS_PREFIX.length);
    let i = app.findAgentById(id);
    if (i !== -1) {
      app.agents[i].available = true;
    }
  });
};
