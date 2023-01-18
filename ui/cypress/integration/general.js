const routes = [
  "/",
  "/workflows/",
  "/processchains/",
  "/agents/",
  "/vms/",
  "/services/"
]
let sidebar = ".sidebar > nav"

routes.forEach(route => {
  describe(`Sidebar from '${route}'`, () => {
    beforeEach(() => {
      cy.visit(route)
    })

    it("can access main Page", () => {
      cy.get(".sidebar").find(".steep-logo").click()
      cy.url().should("include", "/")
    })

    it("can access worksflows page from", () => {
      cy.get(sidebar)
        .children()
        .eq(0)
        .should("be.visible")
        .should("contains.text", "Workflows")
      cy.get(sidebar)
        .children()
        .eq(0)
        .find("a")
        .invoke("attr", "href")
        .should("include", "/workflows/")
    })

    it("can access process chain page", () => {
      cy.get(sidebar)
        .children()
        .eq(1)
        .should("be.visible")
        .should("contains.text", "Process Chains")
      cy.get(sidebar)
        .children()
        .eq(1)
        .find("a")
        .invoke("attr", "href")
        .should("include", "/processchains/")
    })

    it("can access agents page from", () => {
      cy.get(sidebar)
        .children()
        .eq(2)
        .should("be.visible")
        .should("contains.text", "Agents")
      cy.get(sidebar)
        .children()
        .eq(2)
        .find("a")
        .invoke("attr", "href")
        .should("include", "/agents/")
    })

    it("can access VMs page", () => {
      cy.get(sidebar)
        .children()
        .eq(3)
        .should("be.visible")
        .should("contains.text", "VMs")
      cy.get(sidebar)
        .children()
        .eq(3)
        .find("a")
        .invoke("attr", "href")
        .should("include", "/vms/")
    })

    it("can access services page", () => {
      cy.get(sidebar)
        .children()
        .eq(4)
        .should("be.visible")
        .should("contains.text", "Services")
      cy.get(sidebar)
        .children()
        .eq(4)
        .find("a")
        .invoke("attr", "href")
        .should("include", "/services/")
    })
  })
})

routes.forEach(route => {
  describe(`Footer '${route}'`, () => {
    beforeEach(() => {
      cy.visit("/")
    })

    it("can access Fraunhofer logo", () => {
      cy.get(".footer-content")
        .find(".logo")
        .children("a")
        .should("be.visible")
        .invoke("attr", "href")
        .should("eq", "https://igd.fraunhofer.de")
    })

    it("can access home page", () => {
      // prettier-ignore
      cy.get(".footer-content")
        .find("[href=\"https://steep-wms.github.io/\"]")
        .should("be.visible")
        .invoke("attr", "href")
        .should("eq", "https://steep-wms.github.io/")
    })

    it("can access documentation", () => {
      // prettier-ignore
      cy.get(".footer-content")
        .find("[href=\"https://steep-wms.github.io/#documentation\"]")
        .should("be.visible")
        .invoke("attr", "href")
        .should("eq", "https://steep-wms.github.io/#documentation")
    })

    it("can access GitHub", () => {
      // prettier-ignore
      cy.get(".footer-content")
        .find("[href=\"https://github.com/steep-wms/steep\"]")
        .should("be.visible")
        .invoke("attr", "href")
        .should("eq", "https://github.com/steep-wms/steep")
    })
  })
})
