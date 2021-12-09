const ImageMinimizerPlugin = require("image-minimizer-webpack-plugin")

const isProd = process.env.NODE_ENV === "production"

module.exports = {
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
      "/agents": { page: "/agents" },
      "/agents/[id].html": { page: "/agents/[id]" },
      "/logs/processchains/[id].html": { page: "/logs/processchains/[id]" },
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
        },
        "sass-loader"
      ]
    })

    config.module.rules.push({
      test: /\.svg$/,
      use: [
        {
          loader: "file-loader",
          options: {
            name: "static/chunks/[path][name].[ext]"
          }
        }
      ]
    })

    // optimize images in production mode
    if (!dev) {
      config.optimization.minimizer.push(new ImageMinimizerPlugin({
        minimizerOptions: {
          plugins: [
            "svgo"
          ]
        }
      }))
    }

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
