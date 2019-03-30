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
