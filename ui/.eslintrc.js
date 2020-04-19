module.exports = {
  env: {
    browser: true,
    es6: true,
    node: true
  },
  extends: [
    "eslint:recommended",
    "plugin:react/recommended",
    "plugin:react-hooks/recommended",
  ],
  globals: {
    Atomics: "readonly",
    SharedArrayBuffer: "readonly"
  },
  settings: {
    react: {
      version: "detect"
    }
  },
  parserOptions: {
    ecmaFeatures: {
      jsx: true
    },
    ecmaVersion: 2018,
    sourceType: "module"
  },
  plugins: [
    "react"
  ],
  rules: {
    "comma-dangle": ["error", "never"],
    "comma-spacing": ["error", { "before": false, "after": true }],
    "comma-style": ["error", "last"],
    "eol-last": "error",
    "eqeqeq": ["error", "always"],
    "no-multiple-empty-lines": ["error", { max: 1 }],
    "no-tabs": "error",
    "no-trailing-spaces": "error",
    "no-var": "error",
    "object-curly-spacing": ["error", "always"],
    "quotes": ["error", "double"],
    "react/display-name": "off",
    "react/jsx-curly-spacing": ["error", "never"],
    "react/prop-types": "off",
    "react/react-in-jsx-scope": "off",
    "semi": ["error", "never"]
  }
}
