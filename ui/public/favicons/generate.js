import favicons from "favicons"
import fs from "fs"

const config = {
  path: "/favicons/",
  appName: "Steep Workflow Management System",
  appShortName: "Steep",
  appDescription: "Web-based user interface of Steep",
  developerName: "Fraunhofer IGD",
  start_url: "../",
  logging: true
}

let response = await favicons("../../assets/steep-icon.svg", config)

console.log(response)

for (let f of response.files) {
  console.log(`Write ${f.name} ...`)
  fs.writeFileSync(f.name, f.contents, "utf-8")
}

for (let f of response.images) {
  console.log(`Write ${f.name} ...`)
  fs.writeFileSync(f.name, f.contents)
}

console.log("---------------------------------------------------------------")
console.log("INSERT THE FOLLOWING INTO YOUR Header.jsx")
console.log("---------------------------------------------------------------")
for (let s of response.html) {
  s = s.replace(/"\/favicons\/([^"]*)"/g, "{`${router.basePath}/favicons/$1`}")
  s = s.replace(/>$/m, "/>")
  console.log(s)
}
