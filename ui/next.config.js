const bundleAnalyzer = require("@next/bundle-analyzer")({
  enabled: process.env.ANALYZE === "true"
})
const optimizedImages = require("next-optimized-images")
const sass = require("@zeit/next-sass")

const withPlugins = require("next-compose-plugins")

const isProd = process.env.NODE_ENV === "production"

const config = {
  env: {
    // URL to Steep. Used to connect to the event bus.
    // Magic string will be replaced by Steep's HttpEndpoint verticle
    baseUrl: isProd ? "/$$MYBASEURL$$" : "http://localhost:8080"
  },

  // create a folder for each page
  exportTrailingSlash: true,

  experimental: {
    // Base path of the application
    // Magic string will be replaced by Steep's HttpEndpoint verticle
    basePath: isProd ? "/$$MYBASEPATH$$" : ""
  },

  // list pages to export
  exportPathMap() {
    return {
      "/": { page: "/" },
      "/processchains": { page: "/processchains" },
      "/processchains/[id].html": { page: "/processchains/[id]" },
      "/services": { page: "/services" },
      "/services/[id].html": { page: "/services/[id]" },
      "/workflows": { page: "/workflows" },
      "/workflows/[id].html": { page: "/workflows/[id]" }
    }
  },

  webpack: (config, { dev }) => {
    if (dev) {
      config.module.rules.push({
        test: /\.jsx?$/,
        loader: "eslint-loader",
        exclude: [/node_modules/, /\.next/, /out/],
        enforce: "pre",
        options: {
          emitWarning: true
        }
      })
    }
    return config
  }
}

module.exports = withPlugins([
  [optimizedImages],
  [sass],
  [bundleAnalyzer]
], config)
