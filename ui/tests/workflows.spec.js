import { test, expect } from "@playwright/test"

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

test("check tooltips and labels", async({ page, request }) => {
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

  // check if list contains new row for the workflow
  let row = page.locator(`div.list-item:has(a:text("${submissionId}"))`)
  await expect(row).toBeVisible()

  // check if tooltip becomes visible if we hover over the start time
  let startTime = row.locator("time")
  await expect(startTime).not.toBeEmpty()
  let tooltip = row.locator(".tooltip")
  await expect(tooltip).not.toHaveClass(/visible/)
  await expect(tooltip).not.toBeVisible()
  await startTime.hover()
  await expect(tooltip).toHaveClass(/visible/)
  await expect(tooltip).toBeVisible()
  await expect(tooltip).not.toBeEmpty()

  // visit details page
  let link = row.locator(`a:text("${submissionId}")`)
  await link.click()

  // check if workflow is displayed as 'Running'
  let status = page.locator(".list-item-progress-box")
  await expect(status).toContainText("Running")

  // check start, end, and elapsed time
  let labels = page.locator(".detail-header-left .definition-list-content")
  let start = labels.nth(0)
  let end = labels.nth(1)
  let elapsed = labels.nth(2)
  await expect(start).not.toHaveText("\u2013")
  await expect(end).toHaveText("\u2013")
  await expect(elapsed).not.toHaveText("\u2013")

  let labelsMiddle = page.locator(".detail-header-middle .definition-list-content")
  let priority = labelsMiddle.nth(0)
  await expect(priority).toHaveText("0 (normal)")

  // check if workflow status changes to 'Success'
  await expect(status).toContainText("Success", { timeout: 10000 })

  // check labels again
  await expect(start).not.toHaveText("\u2013")
  await expect(end).not.toHaveText("\u2013")
  await expect(elapsed).not.toHaveText("\u2013")
  let elapsedSecondsStr = await elapsed.textContent()
  expect(elapsedSecondsStr).toMatch(/[0-9]+s/)
  let elapsedSeconds = +(elapsedSecondsStr.match(/([0-9])+s/)[1])
  expect(elapsedSeconds).toBeGreaterThanOrEqual(3)

  page.goBack()
  await expect(row).toBeVisible()

  startTime = row.locator("time")
  await expect(startTime).not.toBeEmpty()
  let tooltip1 = row.locator(".tooltip").nth(0)
  await expect(tooltip1).not.toHaveClass(/visible/)
  await expect(tooltip1).not.toBeVisible()
  await startTime.hover()
  await expect(tooltip1).toHaveClass(/visible/)
  await expect(tooltip1).toBeVisible()
  await expect(tooltip1).not.toBeEmpty()

  let endTime = row.locator(":text('a few seconds')")
  await expect(endTime).toBeVisible()
  let tooltip2 = row.locator(".tooltip").nth(1)
  await expect(tooltip2).not.toHaveClass(/visible/)
  await expect(tooltip2).not.toBeVisible()
  await endTime.hover()
  await expect(tooltip2).toHaveClass(/visible/)
  await expect(tooltip2).toBeVisible()
  await expect(tooltip2).not.toBeEmpty()

  await expect(tooltip1).not.toBeVisible()
  await expect(tooltip1).not.toHaveClass(/visible/)
})

test("change workflow priority", async({ page, request }) => {
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

  // visit details page
  await page.click(`a:text("${submissionId}")`)

  // click priority label
  await page.click(":text('0 (normal)')")

  // change priority in input field, which should now have appeared
  let priorityInput = page.locator("input[value='0']")
  await priorityInput.fill("100")
  await priorityInput.press("Enter")

  // confirm
  await page.click("button:text('Change priority')")

  let newLabel = page.locator(":text('100 (higher)')")
  await expect(newLabel).toBeVisible()

  // cancel workflow
  await request.put(`/workflows/${submissionId}`, {
    data: {
      status: "CANCELLED"
    }
  })

  // wait for the workflow to finish
  let status = page.locator(".list-item-progress-box")
  await expect(status).toContainText("Cancelled")

  // priority should not be editable anymore
  await expect(newLabel).toBeVisible()
  await newLabel.click()
  await expect(page.locator("input[value='100']")).not.toBeVisible()
  await expect(newLabel).toBeVisible()
})
