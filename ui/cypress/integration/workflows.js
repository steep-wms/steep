const timeoutLength = 5
const payload = {
  api: "4.6.0",
  vars: [
    {
      id: "sleep_seconds",
      value: timeoutLength
    }
  ],
  actions: [
    {
      type: "execute",
      service: "sleep",
      inputs: [
        {
          id: "seconds",
          var: "sleep_seconds"
        }
      ]
    }
  ]
}

const payloadCancelled = {
  status: "CANCELLED"
}

describe("Workflows page", () => {
  before(() => {
    cy.visit("/workflows")
  })

  it("can access filter dropdown menu", () => {
    const options = [
      "Failed workflows only",
      "Partially succeeded workflows only",
      "Running workflows only"
    ]
    const params = [
      "/?status=ERROR",
      "/?status=PARTIAL_SUCCESS",
      "/?status=RUNNING"
    ]
    cy.get(".dropdown-btn > span").contains("Filter")
    cy.get(".dropdown-btn").should("be.visible")
    for (let i in options) {
      cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).should(
        "not.be.visible"
      )
    }

    cy.get(".dropdown-btn").click()

    for (let i in options) {
      cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).should(
        "be.visible"
      )
      cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).should(
        "have.text",
        options[i]
      )
    }

    for (let i in options) {
      cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).click()
      cy.url().should("include", params[i])
      cy.get(".dropdown-btn").click()
    }
    cy.get(".dropdown-btn").click()
  })
})

describe("Workflow page successfully done", () => {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then(response => {
      res = response
      cy.visit("/workflows")
    })
  })

  after(() => {
    cy.request("PUT", `/workflows/${res.body.id}`, payloadCancelled)
  })

  it("has list item with correct name", () => {
    cy.contains(res.body.id)
  })

  it("List item has correct redirecting done", () => {
    cy.contains(res.body.id).click()
    cy.url().should("include", `/workflows/${res.body.id}/`)
  })
})

describe("Workflow page cancelling", () => {
  let res

  before(() => {
    cy.request("POST", "/workflows", payload).then(response => {
      // refresh
      res = response
      cy.visit("/workflows")
    })
  })

  it(
    "Cancels an exisiting workflow",
    { retries: { runMode: 3, openMode: 0 } },
    () => {
      cy.visit(`/workflows/${res.body.id}/`)
      cy.contains(res.body.id).click()
      cy.get(".dropdown-btn").click()
      cy.get("li").click()
      cy.get(".btn-error").click()
      cy.visit("/workflows")
      cy.get(".list-page")
        .contains(res.body.id)
        .parentsUntil(".list-page")
        .find(".list-item-right > .list-item-progress-box > div > strong")
        .then($el => {
          cy.wrap($el).should("have.text", "Cancelled", {
            timeout: 60 * 10 * 1000
          })
        })
    }
  )
})

describe("has table", () => {
  let res = []
  before(() => {
    for (let i = 0; i < 20; i++) {
      cy.request("POST", "/workflows", payload).then(response => {
        res.unshift(response)
        cy.wait(50)
      })
    }
  })

  beforeEach(() => {
    cy.visit("workflows")
  })

  after(() => {
    res.forEach(ob => {
      cy.request("PUT", `/workflows/${ob.body.id}`, payloadCancelled)
    })
  })

  it("shows all items", () => {
    for (let i = 2; i <= 11; i++) {
      cy.get(
        `.list-page > :nth-child(${i}) > .list-item-left > .list-item-title > a`,
        { timeout: 10000 }
      ).should("have.text", res[i - 2].body.id)
    }
    cy.get(".pagination > :last").click()
    for (let i = 2; i <= 11; i++) {
      cy.get(
        `.list-page > :nth-child(${i}) > .list-item-left > .list-item-title > a`,
        { timeout: 10000 }
      ).should("have.text", res[i - 2 + 10].body.id)
    }
  })

  it("has time dialog box", () => {
    for (let i = 2; i <= 11; i++) {
      cy.get(`:nth-child(${i}) > .list-item-left > .list-item-subtitle`).should(
        "be.visible"
      )
      cy.get(
        `.list-page > :nth-child(${i}) > .list-item-left > .list-item-subtitle > .tooltip`,
        { timeout: 10000 }
      ).should("be.not.visible")
      cy.get(
        `.list-page > :nth-child(${i}) > .list-item-left > .list-item-subtitle > span > time`,
        { timeout: 10000 }
      ).trigger("mouseover")
      cy.get(
        `.list-page > :nth-child(${i}) > .list-item-left > .list-item-subtitle > .tooltip`,
        { timeout: 10000 }
      ).should("be.visible")
    }
  })

  it("shows running workflows", () => {
    cy.get(".dropdown-btn").click()
    cy.get(".dropdown-menu > ul > :nth-child(3)").click()
    cy.wait(2000)
    cy.get(".list-page")
      .children()
      .each(($el, index, $list) => {
        if (index !== 0 && index !== $list.length - 1) {
          cy.wrap($el).contains("Running")
          cy.wrap($el)
            .find(
              ".list-item-right > .list-item-progress-box > .feather.running"
            )
            .should("be.visible")
        }
      })
  })

  it("triggers refresh dialog on second page", () => {
    cy.visit("/workflows/?offset=10")
    cy.get(".notification").should("not.exist")
    cy.wait(1000)
    cy.request("POST", "/workflows", payload)
    cy.get(".notification").should("be.visible")
  })
})
