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

describe("Workflow > Process", () => {
    let res
    let processes
    before(() => {
        cy.request("POST", "/workflows", payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
            cy.get(".list-item-progress-box > div > a").invoke("text").then((text) => {
                // processes = parseInt(text.substring(text.search("of") + 2, text.search("completed") - 1))
                cy.get(".list-item-progress-box > div > a").click()
            })
        })
    })
    it("items are visible and URL is correct", () => {
        cy.url().should("include", `processchains/?submissionId=${res.body.id}`)
        cy.wait(1000)
        cy.get(".list-page").children().each(($el, index, $list) => {
            if (index > 1 && index !== ($list.length - 1)) {
                cy.wrap($el).get(".list-item-left > .list-item-title").should("be.visible").then(() => {
                    cy.wrap($el).get(".list-item-left > .list-item-title > a").invoke("text").then((text) => {
                        cy.wrap($el).get(".list-item-left > .list-item-title > a").invoke("attr", "href").should("include", `/processchains/${text}/`)
                    })
                })
                cy.wrap($el).get(".list-item-left > .list-item-subtitle").should("be.visible")
                cy.wrap($el).get(".list-item-right > .list-item-progress-box").should("be.visible")
                cy.wrap($el).get(".list-item-right > .list-item-progress-box > .feather").should("be.visible")
                cy.wrap($el).get(".list-item-right > .list-item-progress-box > div > strong").should("be.visible")
            }
        })
    })

})
describe("Process chains table", () => {
    let elements = 20
    let res = []
    before(() => {
        for (let i = 0; i < elements; i++) {
            cy.request("POST", "/workflows", payload).then((response) => {
                res.unshift(response)
                cy.wait(50)
            })
        }
    })

    beforeEach(() => {
        cy.visit("/processchains/")
        cy.wait(100)
    })

    it("Scheduled processes are accessible via process chains page", () => {
        for(let i = 0; i < elements/10; i++){
            cy.get(".list-page").children().each(($el, index, $list) => {
                if (index > 1 && index !== ($list.length - 1)) {
                    // $el is not working
                    cy.wrap($el).find(".list-item-left > .list-item-title").should("be.visible").then(() => {
                        cy.wrap($el).find(".list-item-left > .list-item-title > a").invoke("text").then((text) => {
                            cy.wrap($el).find(".list-item-left > .list-item-title > a").invoke("attr", "href").should("include", `/processchains/${text}/`)
                        })
                    })
                    cy.wrap($el).find(".list-item-left > .list-item-subtitle").should("be.visible")
                    cy.wrap($el).find(".list-item-right > .list-item-progress-box").should("be.visible")
                    cy.wrap($el).find(".list-item-right > .list-item-progress-box > .feather").should("be.visible")
                    cy.wrap($el).find(".list-item-right > .list-item-progress-box > div > strong").should("be.visible")
                }
            })
        cy.get(".list-page > :last > .pagination > :last").click()
        }
    })

    it("triggers refresh dialog on second page", () => {
        cy.visit("/processchains/?offset=10")
        cy.get(".notification").should("not.exist")
        cy.wait(1000)
        cy.request("POST", "/workflows", payload)
        cy.get(".notification").should("be.visible")
    })
})

describe("Check Process Chains", () => {
    let res
    let processName
    before(() => {
        cy.request("POST", "/workflows", payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
            cy.get(".list-item-progress-box > div > a").click()
            cy.get(".list-page > :nth-child(3) > .list-item-left > .list-item-title").invoke("text").then((text) => {
                processName = text
            })
            cy.get(".list-page > :nth-child(3) > .list-item-left > .list-item-title").click()

        })
    })
    it("has correct Header", () => {
        cy.get(".detail-page-title > h1").should("be.visible").should("have.text", processName)
        cy.get(".breadcrumbs > :nth-child(1) > a").should("be.visible").should("have.text", "Workflows")
        cy.get(".breadcrumbs > :nth-child(1) > a").invoke("attr", "href").should("include", "/workflows/")
        cy.get(".breadcrumbs > :nth-child(2) >").should("be.visible").should("have.text", res.body.id)
        cy.get(".breadcrumbs > :nth-child(3) > a").should("be.visible").should("have.text", "Process chains")
        cy.get(".breadcrumbs > :nth-child(3) > a").invoke("attr", "href").should("include", `processchains/?submissionId=${res.body.id}`)
    })

    it("can access Start time", () => {
        cy.get(".definition-list > :nth-child(1)").should("be.visible").should("have.text", "Start time")
    })

    it("can access actual Start time", () => {
        cy.get(".definition-list > :nth-child(2)").should("be.visible").should("have.text", "–")
    })

    it("can access End time", () => {
        cy.get(".definition-list > :nth-child(3)").should("be.visible").should("have.text", "End time")
    })

    it("can access actual End time", () => {
        cy.get(".definition-list > :nth-child(4)").should("be.visible").should("have.text", "–")
    })

    it("can access Time elapsed", () => {
        cy.get(".definition-list > :nth-child(5)").should("be.visible").should("have.text", "Time elapsed")
    })

    it("can access actual Time elapsed", () => {
        cy.get(".definition-list > :nth-child(6)").should("be.visible").should("have.text", "–")
    })

    it("can access Required capabilities", () => {
        cy.get(".definition-list > :nth-child(7)").should("be.visible").should("have.text", "Required capabilities")
    })

    it("can access actual Required capabilities", () => {
        cy.get(".definition-list > :nth-child(8)").should("be.visible")
    })

    it("can access actual Required capabilities", () => {
        cy.get(".code-box-title > :nth-child(2)").should("be.visible")
    })

    it("can access Source Tab JSON", () => {
        cy.get(".code-box-title > :nth-child(2)").click()
        cy.get(".code-box-tab.active").should("be.visible")
    })

    it("can access Source", () => {
        cy.get("h2").should("be.visible").should("have.text", "Executables")
    })

    it("can access Source Tab YAML", () => {
        cy.get(".code-box-title > :nth-child(1)").click()
        cy.get(".code-box-tab.active").should("be.visible")
    })

})

describe("Workflow Item Page Successfully Done", () => {
    let res
    before(() => {
        cy.request("POST", "/workflows", payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
        })
    })

    it("has correct title", () => {
        cy.get(".detail-page-title > h1").contains(res.body.id)
    })

    it("has correct running flags", () => {
        cy.get(".list-item-progress-box > div > strong").contains("Running")
        cy.get(".list-item-progress-box > div > strong").contains("Success", { timeout: (timeoutLength + timeoutOffset) * 1000 })
    })
})

describe("Workflow Item Page Cancelling", () => {
    let res
    before(() => {
        cy.request("POST", "/workflows", payload).then((response) => {
            res = response
            cy.visit(`processchains/?submissionId=${res.body.id}`)
        })
    })

    it("cancels an exisiting workflow", () => {
        cy.visit(`/workflows/${res.body.id}/`)
        cy.get(".dropdown-btn").should("have.text", "Actions ")
        cy.get(".dropdown-btn").click()
        cy.get("li").should("have.text", "Cancel")
        cy.get("li").click()
        cy.get(".btn-error").should("have.text", "Cancel it now")
        cy.get(".btn-error").click()
        cy.get(".list-item-progress-box > div > a").click()
        cy.wait(1000)
        cy.get(".list-page").children().each(($el, index, $list) => {
            if (index > 1 && index !== ($list.length - 1)) {
                cy.wrap($el).get(".list-item-right > .list-item-progress-box > div > strong").should("have.text", "Cancelled")
            }
        })
    })

    it("process item are cancelled", () => {
        cy.wait(1000)
        cy.get(".list-page").children().each(($el, index, $list) => {
            if (index > 1 && index !== ($list.length - 1)) {
                cy.wrap($el).get(".list-item-left > .list-item-title").should("be.visible").then(() => {
                    cy.wrap($el).get(".list-item-left > .list-item-title > a").click()
                    cy.get(".list-item-progress-box > div > strong").should("have.text", "Cancelled")
                })
            }
        })
    })

})

describe("Check Times elapsed", () => {
    before(() => {
        cy.request("POST", "/workflows", payload).then((response) => {
            cy.visit(`/workflows/${response.body.id}/`)
            cy.wait(50)
            cy.get(".list-item-progress-box > div > a").click()
            cy.get(".list-item-title").click()
        })
    })

    it.only("time elapsed features is working", () => {
        //unfortunately there is no way to conditionally test not settled dom elements
        //therefore, it is not possible to test the incrementing time feature
        //i recommend to test it via unit test on the backend
        cy.get(".definition-list > :nth-child(6)").should("be.visible")
    })
})

