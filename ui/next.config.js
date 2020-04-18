const optimizedImages = require("next-optimized-images");
const sass = require("@zeit/next-sass")

const withPlugins = require("next-compose-plugins")

const config = {
  env: {
    buildYear: new Date().getFullYear()
  },

  // create a folder for each page
  exportTrailingSlash: true,

  // list pages to export
  exportPathMap() {
    return {
      "/": { page: "/" },
      "/workflows": { page: "/workflows" }
    };
  }
}

module.exports = withPlugins([
  [optimizedImages],
  [sass],
], config)
