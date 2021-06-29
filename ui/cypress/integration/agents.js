
// it is not possible to truly generate tests dynamically
const timeoutOffset = 5
const timeoutLength = 5
const payload = {
    "api": "4.2.0",
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

describe("Agents Main Page", () => {
    beforeEach(() => {
        cy.visit("/agents/")
    })

    it("Title", () => {
        cy.get("h1").should("be.visible").should("have.text", "Agents")
    })

    it("shows all items", () => {
        cy.wait(50)
        cy.get(".list-page").eq(0).should("be.visible")
        cy.get(".list-page").eq(0).find(".list-item-subtitle > span").trigger("mouseover")
        cy.get(".list-page").eq(0).find(".list-item-subtitle > .tooltip").should("be.visible")
        cy.get(".list-page").eq(0).find(".list-item-progress-box > .feather").should("be.visible")
        cy.get(".list-page").eq(0).find(".list-item-progress-box > div > span").should("be.visible")
        cy.get(".list-page").eq(0).find(".list-item-title").should("be.visible").click()
    })
})

describe("Agents Item Page", () => {
    let id
    beforeEach(() => {
        cy.visit("/agents/")
        cy.get(".list-page").eq(0).find(".list-item-title").invoke("text").then((name) => {
            id = name
        })
        cy.get(".list-page").eq(0).find(".list-item-title").should("be.visible").click()
    })

    it("has correct Header", () => {
        cy.get(".detail-page-title > h1").should("be.visible").should("have.text", id)
        cy.get(".breadcrumbs > :nth-child(2)").should("be.visible").should("have.text", id)
        cy.get(".breadcrumbs > :nth-child(1) > a").should("be.visible").should("have.text", "Agents")
        cy.get(".breadcrumbs > :nth-child(1) > a").invoke("attr", "href").should("include", "/agents/")
    })

    it("can access Start time", () => {
        cy.get(".definition-list > :nth-child(1)").should("be.visible").should("have.text", "Start time")
    })

    it("can access actual Start time", () => {
        cy.get(".definition-list > :nth-child(2)").should("be.visible").should("have.not.text", "–")
    })

    it("can access Uptime", () => {
        cy.get(".definition-list > :nth-child(3)").should("be.visible").should("have.text", "Uptime")
    })

    it("can access actual Uptime", () => {
        cy.get(".definition-list > :nth-child(4)").should("be.visible").should("have.not.text", "–")
    })

    it("can access Capabilities", () => {
        cy.get(".definition-list > :nth-child(5)").should("be.visible").should("have.text", "Capabilities")
    })

    it("can access actual Time elapsed", () => {
        cy.get(".definition-list > :nth-child(6)").should("be.visible").should("have.text", "–")
    })

    it("can access Allocated process chain", () => {
        cy.get(".definition-list > :nth-child(7)").should("be.visible").should("have.text", "Allocated process chain")
    })

    it("can access actual Required capabilities", () => {
        cy.get(".definition-list > :nth-child(8)").should("be.visible").should("have.text", "–")
    })

    it.only("has correct running flags", () => {
        cy.get(".list-item-progress-box > div > strong").contains("Idle")
        cy.request("POST", "/workflows", payload)
        cy.get(".definition-list > :nth-child(8)").should("be.visible").should("have.not.text", "–")
        cy.get(".list-item-progress-box > div > strong").contains("Busy", { timeout: (timeoutLength + timeoutOffset) * 1000 })
        cy.get(".list-item-progress-box > div > strong").contains("Idle", { timeout: (timeoutLength + timeoutOffset) * 1000 })
    })
})
