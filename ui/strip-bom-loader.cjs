// webpack loader that strips UTF-8 BOM from source
exports.default = function (source) {
  if (source.charCodeAt(0) === 0xfeff) {
    return source.slice(1)
  }
  return source
}
