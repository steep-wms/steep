Vue.use(VueTimeago);
Vue.use(vueMoment);

function initWorkflow(w) {
  if (!window.singleWorkflow) {
    delete w.workflow;
  }
  w.runningProcessChains = w.runningProcessChains || 0;
  w.succeededProcessChains = w.succeededProcessChains || 0;
  w.failedProcessChains = w.failedProcessChains || 0;
  w.totalProcessChains = w.totalProcessChains || 0;
  w.startTime = w.startTime || null;
  w.endTime = w.endTime || null;
}

if (window.singleWorkflow === undefined) {
  window.singleWorkflow = false;
}

workflows.forEach(initWorkflow);

let app = new Vue({
  el: '#app',
  mixins: [paginationMixin],
  data: {
    workflows: window.workflows,
    page: window.page,
    workflowsAdded: false,
    now: new Date()
  },
  created: function () {
    setInterval(() => {
       this.$data.now = new Date();
    }, 1000);
  },
  methods: {
    findWorkflowById: function (id) {
      for (let workflow of this.workflows) {
        if (workflow.id === id) {
          return workflow;
        }
      }
      return undefined;
    },

    workflowDuration: function (w) {
      let endTime = w.endTime || this.now;
      let duration = Math.ceil(this.$moment.duration(
          this.$moment(endTime).diff(this.$moment(w.startTime))).asSeconds());
      let seconds = Math.floor(duration % 60);
      let minutes = Math.floor(duration / 60 % 60);
      let hours = Math.floor(duration / 60 / 60);
      let result = "";
      if (hours > 0) {
        result += hours + "h ";
      }
      if (result !== "" || minutes > 0) {
        result += minutes + "m ";
      }
      result += seconds + "s";
      return result;
    }
  }
});

$(".message .close").on("click", () => {
  app.workflowsAdded = false;
});

let eb = new EventBus("/eventbus");
eb.enableReconnect(true);
eb.onopen = () => {
  if (!window.singleWorkflow) {
    eb.registerHandler("jobmanager.submissionRegistry.submissionAdded", (error, message) => {
      if (page.offset > 0) {
        app.workflowsAdded = true;
      } else {
        let w = message.body;
        initWorkflow(w);
        app.workflows.unshift(w);
        if (app.workflows.length > app.page.size) {
          app.workflows.pop();
        }
        app.page.total++;
      }
    });
  }

  eb.registerHandler("jobmanager.submissionRegistry.submissionStartTimeChanged", (error, message) => {
    let w = app.findWorkflowById(message.body.submissionId);
    if (w) {
      w.startTime = message.body.startTime;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.submissionEndTimeChanged", (error, message) => {
    let w = app.findWorkflowById(message.body.submissionId);
    if (w) {
      w.endTime = message.body.endTime;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.submissionStatusChanged", (error, message) => {
    let w = app.findWorkflowById(message.body.submissionId);
    if (w) {
      w.status = message.body.status;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.processChainsAdded", (error, message) => {
    let pcs = message.body.processChains;
    let submissionId = message.body.submissionId;
    let status = message.body.status;
    let w = app.findWorkflowById(submissionId);
    if (!w) {
      return;
    }

    for (let pc of pcs) {
      window.processChains[pc.id] = {
        submissionId: submissionId,
        status: status
      };
    }

    w.totalProcessChains += pcs.length;

    if (status === "RUNNING") {
      w.runningProcessChains += pcs.length;
    } else if (status === "ERROR") {
      w.failedProcessChains += pcs.length;
    } else if (status === "SUCCESS") {
      w.succeededProcessChains += pcs.length;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.processChainStatusChanged", (error, message) => {
    let processChainId = message.body.processChainId;
    let status = message.body.status;
    let pc = window.processChains[processChainId];
    if (!pc) {
      return;
    }

    let w = app.findWorkflowById(pc.submissionId);
    if (!w) {
      return;
    }

    if (pc.status !== status) {
      if (pc.status === "RUNNING") {
        w.runningProcessChains--;
      } else if (pc.status === "ERROR") {
        w.failedProcessChains--;
      } else if (pc.status === "SUCCESS") {
        w.succeededProcessChains--;
      }

      if (status === "RUNNING") {
        w.runningProcessChains++;
      } else if (status === "ERROR") {
        w.failedProcessChains++;
      } else if (status === "SUCCESS") {
        w.succeededProcessChains++;
      }

      pc.status = status;
    }
  });
};
