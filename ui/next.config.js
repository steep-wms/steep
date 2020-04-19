const bundleAnalyzer = require("@next/bundle-analyzer")({
  enabled: process.env.ANALYZE === "true"
})
const optimizedImages = require("next-optimized-images");
const sass = require("@zeit/next-sass")

const withPlugins = require("next-compose-plugins")

const config = {
  env: {
    baseUrl: "http://localhost:8080"
  },

  // create a folder for each page
  exportTrailingSlash: true,

  // list pages to export
  exportPathMap() {
    return {
      "/": { page: "/" },
      "/services": { page: "/services" },
      "/workflows": { page: "/workflows" }
    };
  }
}

module.exports = withPlugins([
  [optimizedImages],
  [sass],
  [bundleAnalyzer]
], config)
