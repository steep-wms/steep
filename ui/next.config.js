const bundleAnalyzer = require("@next/bundle-analyzer")({
  enabled: process.env.ANALYZE === "true"
})
const optimizedImages = require("next-optimized-images")

const withPlugins = require("next-compose-plugins")

const isProd = process.env.NODE_ENV === "production"

const config = {
  env: {
    // URL to Steep. Used to connect to the event bus.
    // Magic string will be replaced by Steep's HttpEndpoint verticle
    baseUrl: isProd ? "/$$MYBASEURL$$" : "http://localhost:8080"
  },

  // Base path of the web application
  // Magic string will be replaced by Steep's HttpEndpoint verticle
  basePath: isProd ? "/$$MYBASEPATH$$" : "",

  // create a folder for each page
  trailingSlash: true,

  // do not display static optimization indicator
  // it gets in the way of notifications
  devIndicators: {
    autoPrerender: false
  },

  // list pages to export
  exportPathMap() {
    return {
      "/": { page: "/" },
      "/agents": { page: "/agents" },
      "/agents/[id].html": { page: "/agents/[id]" },
      "/new/workflow": { page: "/new/workflow" },
      "/processchains": { page: "/processchains" },
      "/processchains/[id].html": { page: "/processchains/[id]" },
      "/services": { page: "/services" },
      "/services/[id].html": { page: "/services/[id]" },
      "/vms": { page: "/vms" },
      "/vms/[id].html": { page: "/vms/[id]" },
      "/workflows": { page: "/workflows" },
      "/workflows/[id].html": { page: "/workflows/[id]" }
    }
  },

  webpack: (config, { dev, defaultLoaders }) => {
    config.module.rules.push({
      test: /\.scss$/,
      use: [
        defaultLoaders.babel,
        {
          loader: require("styled-jsx/webpack").loader,
          options: {
            type: (fileName, options) => options.query.type || "scoped"
          }
        }
      ]
    })

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
  [bundleAnalyzer]
], config)
