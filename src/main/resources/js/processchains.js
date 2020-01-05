Vue.use(VueTimeago);
Vue.use(vueMoment);

function initProcessChain(pc) {
  if (!window.singleProcessChain) {
    delete pc.executables;
  }
  pc.startTime = pc.startTime || null;
  pc.endTime = pc.endTime || null;
}

if (window.singleProcessChain === undefined) {
  window.singleProcessChain = false;
}

processChains.forEach(initProcessChain);

let app = new Vue({
  el: '#app',
  mixins: [paginationMixin],
  data: {
    processChains: window.processChains,
    page: window.page,
    processChainsAdded: false,
    now: new Date()
  },
  created: function () {
    setInterval(() => {
       this.$data.now = new Date();
    }, 1000);
  },
  methods: {
    findProcessChainById: function (id) {
      for (let pc of this.processChains) {
        if (pc.id === id) {
          return pc;
        }
      }
      return undefined;
    },

    findProcessChainsBySubmissionIdAndStatus: function (submissionId, status) {
      let result = [];
      for (let pc of this.processChains) {
        if (pc.submissionId === submissionId && pc.status === status) {
          result.push(pc);
        }
      }
      return result;
    },

    processChainDuration: function (pc) {
      let endTime = pc.endTime || this.now;
      let duration = Math.ceil(this.$moment.duration(
          this.$moment(endTime).diff(this.$moment(pc.startTime))).asSeconds());
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
  app.processChainsAdded = false;
});

let eb = new EventBus(basePath + "/eventbus");
eb.enableReconnect(true);
eb.onopen = () => {
  if (!window.singleProcessChain) {
    eb.registerHandler("jobmanager.submissionRegistry.processChainsAdded", (error, message) => {
      let submissionId = message.body.submissionId;
      if (window.submissionId === null || window.submissionId === submissionId) {
        if (page.offset > 0) {
          app.processChainsAdded = true;
        } else {
          let status = message.body.status
          let pcs = message.body.processChains;
          for (pc of pcs) {
            pc.status = status;
            initProcessChain(pc);
            app.processChains.unshift(pc);
            if (app.processChains.length > app.page.size) {
              app.processChains.pop();
            }
            app.page.total++;
          }
        }
      }
    });
  }

  eb.registerHandler("jobmanager.submissionRegistry.processChainStartTimeChanged", (error, message) => {
    let pc = app.findProcessChainById(message.body.processChainId);
    if (pc) {
      pc.startTime = message.body.startTime;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.processChainEndTimeChanged", (error, message) => {
    let pc = app.findProcessChainById(message.body.processChainId);
    if (pc) {
      pc.endTime = message.body.endTime;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.processChainStatusChanged", (error, message) => {
    let pc = app.findProcessChainById(message.body.processChainId);
    if (pc) {
      pc.status = message.body.status;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.processChainAllStatusChanged", (error, message) => {
    let pcs = app.findProcessChainsBySubmissionIdAndStatus(
        message.body.submissionId, message.body.currentStatus);
    for (pc of pcs) {
      pc.status = message.body.newStatus;
    }
  });

  eb.registerHandler("jobmanager.submissionRegistry.processChainErrorMessageChanged", (error, message) => {
    let pc = app.findProcessChainById(message.body.processChainId);
    if (pc) {
      pc.errorMessage = message.body.errorMessage;
    }
  });
};
