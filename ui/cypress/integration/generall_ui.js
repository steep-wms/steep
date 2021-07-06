const routes = ["/", "/workflows/", "/processchains/", "/agents/", "/vms/", "/services/"]
let sidebar = ".sidebar > nav"

     routes.forEach((route) => {
        describe(`Sidebar from '${route}'`, () => {
            beforeEach(() => {
                cy.visit(route)
            })
            it("can access Main Page", () => {
                cy.get(".sidebar").find(".steep-logo").click()
                cy.url().should("include", "/")
            })

            it("can access Worksflows Page from", () => {
                cy.get(sidebar).children().eq(0).should("be.visible").should("contains.text", "Workflows")
                cy.get(sidebar).children().eq(0).find("a").invoke("attr", "href").should("include", "/workflows/")
            })

            it("can access Process Chain Page", () => {
                cy.get(sidebar).children().eq(1).should("be.visible").should("contains.text", "Process Chains")
                cy.get(sidebar).children().eq(1).find("a").invoke("attr", "href").should("include", "/processchains/")
            })

            it("can access Agents Page from", () => {
                cy.get(sidebar).children().eq(2).should("be.visible").should("contains.text", "Agents")
                cy.get(sidebar).children().eq(2).find("a").invoke("attr", "href").should("include", "/agents/")
            })

            it("can access VMS Page", () => {
                cy.get(sidebar).children().eq(3).should("be.visible").should("contains.text", "VMs")
                cy.get(sidebar).children().eq(3).find("a").invoke("attr", "href").should("include", "/vms/")
            })

            it("can access Services Page", () => {
                cy.get(sidebar).children().eq(4).should("be.visible").should("contains.text", "Services")
                cy.get(sidebar).children().eq(4).find("a").invoke("attr", "href").should("include", "/services/")
            })
        })

    })

     routes.forEach((route) => {
        describe(`Footer '${route}'`, () => {
            beforeEach(() => {
                cy.visit("/")
            })

            it("can access Fraunhofer", () => {
                cy.get(".footer-content").find(".logo").children("a").invoke("attr", "href").should("eq", "https://igd.fraunhofer.de")
            })

            it("can access Home Page", () => {
                cy.get(".footer-content").find("[href=\"https://steep-wms.github.io/\"]").invoke("attr", "href").should("eq", "https://steep-wms.github.io/")
            })

            it("can access Documentation", () => {
                cy.get(".footer-content").find("[href=\"https://steep-wms.github.io/#documentation\"]").invoke("attr", "href").should("eq", "https://steep-wms.github.io/#documentation")
            })

            it("can access GitHub from", () => {
                cy.get(".footer-content").find("[href=\"https://github.com/steep-wms/steep\"]").invoke("attr", "href").should("eq", "https://github.com/steep-wms/steep")
            })
        })
    })
