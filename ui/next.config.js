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

  // do not display static optimization indicator
  // it gets in the way of notifications
  devIndicators: {
    autoPrerender: false
  },

  eslint: {
    dirs: ["components", "cypress", "pages"]
  },

  images: {
    // make build compatible with next-optimized-images
    disableStaticImages: true
  },

  // list pages to export
  exportPathMap() {
    return {
      "/": { page: "/" },
      "/agents/index": { page: "/agents" },
      "/agents/[id]": { page: "/agents/[id]" },
      "/logs/processchains/[id]": { page: "/logs/processchains/[id]" },
      "/new/workflow/index": { page: "/new/workflow" },
      "/processchains/index": { page: "/processchains" },
      "/processchains/[id]": { page: "/processchains/[id]" },
      "/services/index": { page: "/services" },
      "/services/[id]": { page: "/services/[id]" },
      "/vms/index": { page: "/vms" },
      "/vms/[id]": { page: "/vms/[id]" },
      "/workflows/index": { page: "/workflows" },
      "/workflows/[id]": { page: "/workflows/[id]" }
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
        },
        "sass-loader"
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
