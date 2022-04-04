const { test, expect } = require("@playwright/test")

test.describe.configure({ mode: "parallel" })

test("submit workflow", async({ page, request }) => {
  let workflow = `api: 4.4.0
actions:
  - type: execute
    service: sleep
    inputs:
      - id: seconds
        value: 1
`

  // submit workflow
  let response = await request.post("/workflows", {
    data: workflow
  })
  expect(response.status()).toBe(202)
  let submissionId = (await response.json()).id

  // visit page after the workflow has been submitted
  await page.goto("/workflows")

  // check if list contains new row for the workflow
  let row = page.locator(`div.list-item:has(a:text("${submissionId}"))`)
  await expect(row).toBeVisible()

  // check if workflow is displayed as 'Running' (or 'Success' if our test was too slow)
  let status = row.locator(".list-item-right")
  await expect(status).toContainText(/Running|Success/)

  // check if workflow status changes to 'Success'
  await expect(status).toContainText("Success")
})

test("submit workflow and check details", async({ page, request }) => {
  // visit page before the workflow has been submitted
  // wait until the workflow list has been loaded
  await page.goto("/workflows", { waitUntil: "networkidle" })

  let workflow = `api: 4.4.0
actions:
  - type: execute
    service: sleep
    inputs:
      - id: seconds
        value: 1
`

  // submit workflow
  let response = await request.post("/workflows", {
    data: workflow
  })
  expect(response.status()).toBe(202)
  let submissionId = (await response.json()).id

  // check if list contains new item for the workflow
  let link = page.locator(`a:text("${submissionId}")`)
  await expect(link).toBeVisible()

  // visit details page
  await link.click()

  // check if workflow is displayed as 'Running' (or 'Success' if our test was too slow)
  let status = page.locator(".list-item-progress-box")
  await expect(status).toContainText(/Running|Success/)

  // check if workflow status changes to 'Success'
  await expect(status).toContainText("Success")
})

test("submit and cancel a workflow", async({ page, request }) => {
  // visit page before the workflow has been submitted
  // wait until the workflow list has been loaded
  await page.goto("/workflows", { waitUntil: "networkidle" })

  let workflow = `api: 4.4.0
actions:
  - type: execute
    service: sleep
    inputs:
      - id: seconds
        value: 30
`

  // submit workflow
  let response = await request.post("/workflows", {
    data: workflow
  })
  expect(response.status()).toBe(202)
  let submissionId = (await response.json()).id

  // check if list contains new item for the workflow
  let link = page.locator(`a:text("${submissionId}")`)
  await expect(link).toBeVisible()

  // visit details page
  await link.click()

  // check if workflow is displayed as 'Running'
  let status = page.locator(".list-item-progress-box")
  await expect(status).toContainText("Running")

  // open drop-down menu and click 'Cancel' link
  let dropDownBtn = page.locator(".detail-page-title .dropdown-btn")
  await dropDownBtn.click()
  let dropDownMenu = page.locator(".detail-page-title .dropdown-menu")
  await expect(dropDownMenu).toHaveClass(/visible/)
  await dropDownMenu.locator("li:text('Cancel')").click()

  // click 'Cancel it now' button
  await page.locator("button:text('Cancel it now')").click()

  // check if workflow status changes to 'Cancelled'
  await expect(status).toContainText("Cancelled")
})

test("submit and don't cancel a workflow", async({ page, request }) => {
  // visit page before the workflow has been submitted
  // wait until the workflow list has been loaded
  await page.goto("/workflows", { waitUntil: "networkidle" })

  let workflow = `api: 4.4.0
actions:
  - type: execute
    service: sleep
    inputs:
      - id: seconds
        value: 3
`

  // submit workflow
  let response = await request.post("/workflows", {
    data: workflow
  })
  expect(response.status()).toBe(202)
  let submissionId = (await response.json()).id

  // check if list contains new item for the workflow
  let link = page.locator(`a:text("${submissionId}")`)
  await expect(link).toBeVisible()

  // visit details page
  await link.click()

  // check if workflow is displayed as 'Running'
  let status = page.locator(".list-item-progress-box")
  await expect(status).toContainText("Running")

  // open drop-down menu and click 'Cancel' link
  let dropDownBtn = page.locator(".detail-page-title .dropdown-btn")
  await dropDownBtn.click()
  let dropDownMenu = page.locator(".detail-page-title .dropdown-menu")
  await expect(dropDownMenu).toHaveClass(/visible/)
  await dropDownMenu.locator("li:text('Cancel')").click()

  // click 'Keep it' button
  await page.locator("button:text('Keep it')").click()

  // check if workflow status changes to 'Success'
  await expect(status).toContainText("Success", { timeout: 10000 })
})
