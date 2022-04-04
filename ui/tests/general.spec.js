const { test, expect } = require("@playwright/test")

const routes = ["/", "/workflows/", "/processchains/", "/agents/", "/vms/", "/services/"]

let page

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage()
})

test("version number", async () => {
  await page.goto("/")
  let version = (await page.locator("dd:below(:text('Version'))")).first()
  await expect(version).not.toBeEmpty()
})

for (let route of routes) {
  test.describe(`page ${route}`, () => {
    test.beforeAll(async () => {
      await page.goto(route)
    })

    test("links in sidebar", async () => {
      let logo = await page.locator(".sidebar-logo")
      await expect(logo).toHaveAttribute("href", "/")

      let sidebar = await page.locator(".sidebar")

      let workflows = await sidebar.locator("a:has-text('Workflows')")
      await expect(workflows).toBeVisible()
      await expect(workflows).toHaveAttribute("href", "/workflows/")

      let processChains = await sidebar.locator("a:has-text('Process chains')")
      await expect(processChains).toBeVisible()
      await expect(processChains).toHaveAttribute("href", "/processchains/")

      let agents = await sidebar.locator("a:has-text('Agents')")
      await expect(agents).toBeVisible()
      await expect(agents).toHaveAttribute("href", "/agents/")

      let vms = await sidebar.locator("a:has-text('VMs')")
      await expect(vms).toBeVisible()
      await expect(vms).toHaveAttribute("href", "/vms/")

      let services = await sidebar.locator("a:has-text('Services')")
      await expect(services).toBeVisible()
      await expect(services).toHaveAttribute("href", "/services/")
    })

    test("links in footer", async () => {
      let footer = await page.locator(".footer-content")

      let logo = await footer.locator(".logo > a")
      await expect(logo).toBeVisible()
      await expect(logo).toHaveAttribute("href", "https://igd.fraunhofer.de")

      let homepage = await footer.locator("[href='https://steep-wms.github.io/']")
      await expect(homepage).toBeVisible()

      let documentation = await footer.locator("[href='https://steep-wms.github.io/#documentation']")
      await expect(documentation).toBeVisible()

      let github = await footer.locator("[href='https://github.com/steep-wms/steep']")
      await expect(github).toBeVisible()
    })
  })
}
