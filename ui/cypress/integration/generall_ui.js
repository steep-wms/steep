const routes = ["/", "/workflows/", "/processchains/", "/agents/", "/vms/", "/services/"]

     routes.forEach((route) => {
        describe(`Sidebar from '${route}'`, () => {
            beforeEach(() => {
                cy.visit(route)
            })
            it(`can access Main Page from '${route}'`, () => {
                cy.get(".sidebar").get(".steep-logo").click()
                cy.url().should("include", "/")
            })

            it(`can access Worksflows Page from '${route}'`, () => {
                cy.get(".sidebar").get(":nth-child(1) > .nav-item").contains("Workflows")
                cy.get(".sidebar").get(":nth-child(1) > .nav-item").click()
                cy.url().should("include", "/workflows/")
            })

            it(`can access Process Chain Page from '${route}'`, () => {
                cy.get(".sidebar").get(":nth-child(2) > .nav-item").contains("Process Chains")
                cy.get(".sidebar").get(":nth-child(2) > .nav-item").click()
                cy.url().should("include", "/processchains/")
            })

            it(`can access Agents Page from '${route}'`, () => {
                cy.get(".sidebar").get(":nth-child(3) > .nav-item").contains("Agents")
                cy.get(".sidebar").get(":nth-child(3) > .nav-item").click()
                cy.url().should("include", "/agents/")
            })

            it(`can access VMS Page from '${route}'`, () => {
                cy.get(".sidebar").get(":nth-child(4) > .nav-item").contains("VMs")
                cy.get(".sidebar").get(":nth-child(4) > .nav-item").click()
                cy.url().should("include", "/vms/")
            })

            it(`can access Services Page from '${route}'`, () => {
                cy.get(".sidebar").get(":nth-child(5) > .nav-item").contains("Services")
                cy.get(".sidebar").get(":nth-child(5) > .nav-item").click()
                cy.url().should("include", "/services/")
            })
        })

    })

     routes.forEach((route) => {
        describe("Footer", () => {
            beforeEach(() => {
                cy.visit("/")
            })

            it(`can access Fraunhofer from '${route}'`, () => {
                cy.get(".footer-content").get(".logo").children("a").invoke("attr", "href").should("eq", "https://igd.fraunhofer.de")
            })

            it(`can access Home Page from '${route}'`, () => {
                cy.get(".footer-content").get("[href=\"https://steep-wms.github.io/\"]").invoke("attr", "href").should("eq", "https://steep-wms.github.io/")
            })

            it(`can access Documentation from '${route}'`, () => {
                cy.get(".footer-content").get("[href=\"https://steep-wms.github.io/#documentation\"]").invoke("attr", "href").should("eq", "https://steep-wms.github.io/#documentation")
            })

            it(`can access GitHub from '${route}'`, () => {
                cy.get(".footer-content").get("[href=\"https://github.com/steep-wms/steep\"]").invoke("attr", "href").should("eq", "https://github.com/steep-wms/steep")
            })
        })
    })
