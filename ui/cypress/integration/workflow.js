const timeoutOffset = 5
const timeoutLength = 5
const numOfActions = 1
const payload = {
  "api": "4.5.0",
  "vars": [{
    "id": "sleep_seconds",
    "value": timeoutLength
  }],
  "actions": [{
    "type": "execute",
    "service": "sleep",
    "inputs": [{
      "id": "seconds",
      "var": "sleep_seconds"
    }]
  }]
}

const payloadCancelled = {
  "status": "CANCELLED"
}

describe("Workflow item page attributes are not hidden", () => {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then(response => {
      res = response
      cy.visit(`/workflows/${response.body.id}/`)
    })
  })

  after(() => {
    cy.request("PUT", `/workflows/${res.body.id}`, payloadCancelled)
  })

  it("has correct header", () => {
    cy.get(".detail-page-title > h1").should("be.visible").should("have.text", res.body.id)
    cy.get(".breadcrumbs > :nth-child(2)").should("be.visible").should("have.text", res.body.id)
    cy.get(".breadcrumbs > :nth-child(1) > a").should("be.visible").should("have.text", "Workflows")
    cy.get(".breadcrumbs > :nth-child(1) > a").invoke("attr", "href").should("include", "/workflows/")
  })

  it("can access actions combobox", () => {
    cy.get(".dropdown-btn").should("have.text", "Actions ").click()
    cy.get("li").should("have.text", "Cancel").click()
    cy.get(".cancel-modal-buttons > :nth-child(1)").should("have.text", "Keep it").click()
    cy.get(".list-item-progress-box > div > strong").contains("Running")
  })

  it("can access start time", () => {
    cy.get(".definition-list > :nth-child(1)").should("be.visible").should("have.text", "Start time")
  })

  it("can access actual start time", () => {
    cy.get(".definition-list > :nth-child(2)").should("be.visible").should("have.not.text", "–")
  })

  it("can access end time", () => {
    cy.get(".definition-list > :nth-child(3)").should("be.visible").should("have.text", "End time")
  })

  it("can access actual end time", () => {
    cy.get(".definition-list > :nth-child(4)").should("be.visible").should("have.text", "–")
  })

  it("can access time elapsed", () => {
    cy.get(".definition-list > :nth-child(5)").should("be.visible").should("have.text", "Time elapsed")
  })

  it("can access actual time elapsed", () => {
    cy.get(".definition-list > :nth-child(6)").should("be.visible").should("have.not.text", "–")
  })

  it("can access required capabilities", () => {
    cy.get(".definition-list > :nth-child(7)").should("be.visible").should("have.text", "Required capabilities")
  })

  it("can access actual required capabilities", () => {
    cy.get(".definition-list > :nth-child(8)").should("be.visible")
  })

  it("can access actual required capabilities", () => {
    cy.get(".code-box-title > :nth-child(2)").should("be.visible")
  })

  it("can access source tab JSON", () => {
    cy.get(".code-box-title > :nth-child(2)").click()
    cy.get(".code-box-tab.active").should("be.visible")
  })

  it("can access source", () => {
    cy.get("h2").should("be.visible").should("have.text", "Source")
  })

  it("can access source tab YAML", () => {
    cy.get(".code-box-title > :nth-child(1)").click()
    cy.get(".code-box-tab.active").should("be.visible")
  })
})

describe("Workflow item page successfully done", () => {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then(response => {
      res = response
      cy.visit(`/workflows/${response.body.id}/`)
    })
  })

  after(() => {
    cy.request("PUT", `/workflows/${res.body.id}`, payloadCancelled)
  })

  it("has correct title", () => {
    cy.get(".detail-page-title > h1").contains(res.body.id)
  })

  it("has correct running flags", () => {
    cy.get(".list-item-progress-box > div > strong").contains("Running")
    cy.get(".list-item-progress-box > div > strong").contains("Success",
        { timeout: (timeoutLength + timeoutOffset) * 1000 })
  })
})

describe("Resubmission", ()=> {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then(response => {
      cy.visit(`/workflows/${response.body.id}/`)
      res = response
    })
  })

  after(() => {
    cy.request("PUT", `/workflows/${res.body.id}`, payloadCancelled)
  })

  // TODO this test has been disabled for the time being. It does not run reliably.
  it.skip("can resubmit", () => {
    cy.get(".list-item-progress-box > div > strong", { timeout: (timeoutLength + timeoutOffset) * 1000 })
      .should("have.text", "Success")
    cy.get(".dropdown-btn").click()
    cy.get("li").click()
    cy.wait(1000)
    cy.get(".buttons > .primary").click()

    cy.get(".list-item-progress-box > div > strong").should("have.text", `${numOfActions} Running`)
    cy.get(".list-item-progress-box > div > a").should("have.text", `0 of ${numOfActions} completed`)

    cy.get(".list-item-progress-box > div > strong", { timeout: (timeoutLength + timeoutOffset) * 1000 })
      .should("have.text", "0 Running")
    cy.get(".list-item-progress-box > div > a", { timeout: (timeoutLength + timeoutOffset) * 1000 })
      .should("have.text", `${numOfActions} of ${numOfActions} completed`)

    cy.get(".list-item-progress-box > div > strong", { timeout: (timeoutLength + timeoutOffset) * 1000 })
      .should("have.text", "Success")
    cy.get(".list-item-progress-box > div > a").should("have.text", `${numOfActions} completed`)
  })
})

describe("Workflow item page cancelling", () => {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then((response) => {
      cy.visit(`/workflows/${response.body.id}/`)
      res = response
    })
  })

  after(() => {
    cy.request("PUT", `/workflows/${res.body.id}`, payloadCancelled)
  })

  it("cancels an existing workflow", () => {
    cy.get(".dropdown-btn").should("have.text", "Actions ").click()
    cy.get("li").should("have.text", "Cancel").click()
    cy.get(".btn-error").should("have.text", "Cancel it now").click()
    cy.get(".list-item-progress-box > div > a").should("have.text", `${numOfActions} of ${numOfActions} completed`)
    cy.get(".list-item-progress-box > div > strong").should("have.text", "Cancelled")
    cy.get(".list-item-progress-box > div > a").should("have.text", `${numOfActions} completed`)
  })
})

describe("Check times elapsed", () => {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then(response => {
      cy.visit(`/workflows/${response.body.id}/`)
      cy.wait(50)
      res = response
    })
  })

  after(() => {
    cy.request("PUT", `/workflows/${res.body.id}`, payloadCancelled)
  })

  it("time elapsed features is working", () => {
    // Unfortunately, there is no way to conditionally test unsettled DOM elements.
    // Therefore, it is not possible to test the incrementing time feature.
    // I recommend to test it via unit test on the backend.
    cy.get(".definition-list > :nth-child(6)").should("be.visible")
  })
})
