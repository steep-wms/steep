Vue.use(vueMoment);

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
    agents: window.agents,
    now: new Date()
  },
  created: function () {
    setInterval(() => {
      this.$data.now = new Date();
    }, 1000);
  },
  methods: {
    sortAgents: function () {
      _sortAgents(this.agents);
    },

    findAgentById: function (id) {
      return this.agents.findIndex(e => e.id === id);
    },

    formatUptime: function (time) {
      let t = time || this.now;
      let duration = Math.ceil(this.$moment.duration(this.$moment(this.now)
          .diff(this.$moment(t))).asSeconds());
      let seconds = Math.floor(duration % 60);
      let minutes = Math.floor(duration / 60 % 60);
      let hours = Math.floor(duration / 60 / 60);
      let result = "";
      if (hours > 1) {
        result += hours + " hours and ";
      } else if (hours == 1) {
        result += hours + " hour and ";
      }
      if (result !== "" || minutes > 0) {
        if (minutes === 1) {
          result += minutes + " minute ";
        } else {
          result += minutes + " minutes ";
        }
      }
      if (result === "") {
        if (seconds === 1) {
          result = seconds + " second";
        } else {
          result = seconds + " seconds";
        }
      } else {
        result = "about " + result;
      }
      return result;
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
      app.agents[i].stateChangedTime = new Date();
    }
  });

  eb.registerHandler("jobmanager.remoteAgentRegistry.agentIdle", (error, message) => {
    let id = message.body.substring(AGENT_ADDRESS_PREFIX.length);
    let i = app.findAgentById(id);
    if (i !== -1) {
      app.agents[i].available = true;
      app.agents[i].stateChangedTime = new Date();
    }
  });
};
