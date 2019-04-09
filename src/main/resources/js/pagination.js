var paginationMixin = {
  computed: {
    visiblePages: function() {
      let min = Math.max(2, this.activePage - 3);
      let max = min + 7;
      if (max > this.totalPages) {
        min -= (max - this.totalPages);
        max = this.totalPages;
      }
      min = Math.max(2, min);
      if (min > 2) {
        min++;
      }
      if (max < this.totalPages) {
        max--;
      }
      return Array.from({ length: max - min }, (v, i) => i + min);
    },

    activePage: function() {
      return Math.floor(page.offset / page.size) + 1;
    },

    totalPages: function() {
      return Math.ceil(page.total / page.size);
    }
  },

  methods: {
    makePageLink: function(n) {
      let r = "?";
      if (typeof submissionId !== "undefined" && submissionId) {
        r += "submissionId=" + submissionId + "&";
      }
      return r + "size=" + this.page.size + "&offset=" + (n - 1) * this.page.size;
    }
  }
};
