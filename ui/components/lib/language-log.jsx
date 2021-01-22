function log() {
  return {
    name: "Log file",
    contains: [{
      // Dash
      className: "subst",
      begin: "^",
      end: " - ",
      contains: [{
        // Timestamp
        className: "number",
        begin: "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"
      }]
    }]
  }
}

module.exports = log
