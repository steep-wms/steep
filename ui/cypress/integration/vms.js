describe("VMs list table", () => {
  beforeEach(() => {
    cy.visit("/vms/")
  })

  it("contains everything needed on the ", () => {
    cy.get(".list-item").each(($el) => {
      cy.wrap($el).find(".list-item-left > .list-item-title > a").should("be.visible").should("have.attr", "href")
      cy.wrap($el).find(".list-item-left > .list-item-title > span").should("be.visible")
      cy.wrap($el).find(".list-item-right > .list-item-progress-box > div > strong").should("be.visible").invoke("text")
      cy.wrap($el).find(".list-item-right > .list-item-progress-box > .feather").should("be.visible")
    })
  })
})

let item_href = ["/vms/aqezhfpwhsbq2dcfgnza/"]

item_href.forEach(href => {
  describe(`${href} VMs item page`, () => {
    it("contains complete header", () => {
      cy.visit(href)
      cy.get(".detail-page-title > h1").should("be.visible").should("include.text", href.split("/")[0])
      cy.get(".breadcrumbs > :nth-child(1) > a").should("be.visible").should("include.text", "VMs")
      cy.get(".breadcrumbs > :nth-child(1) > a").invoke("attr", "href").should("eq", "/vms/")
      cy.get(".breadcrumbs > :nth-child(2)").should("be.visible").should("include.text", href.split("/")[0])
    })

    it("contains complete detail header", () => {
      cy.visit(href)
      cy.get(".list-item-progress-box > div > strong").should("be.visible")
      cy.get(".list-item-progress-box > .feather").should("be.visible")
      cy.get(".detail-header > .detail-header-left > .definition-list").children().should("be.visible").should("have.length", 8).invoke("text")
      let detail_page_main_types = ["Creation time", "Time when the agent joined the cluster", "Destruction time", "Uptime"]
      for (let type of detail_page_main_types) {
        cy.get(".detail-header").contains(type).should("be.visible").invoke("text")
      }
    })

    it("contains an error message", () => {
      cy.get(".detail-page-main > h2").eq(0).should("be.visible").should("include.text", "Error message")
      cy.get(".alert").should("be.visible")
    })

    it("contains complete VM details", () => {
      cy.visit(href)
      cy.get(".detail-page-main > h2").eq(1).should("be.visible").should("include.text", "Details")
      cy.get(".vm-details-two-column").eq(0).children().children().children().should("be.visible").invoke("text")
      let detail_header_types = ["Provided capabilities", "IP address", "External ID", "Flavor", "Image", "Availability zone"]
      for (let type of detail_header_types) {
        cy.get(".vm-details-two-column").contains(type).should("be.visible").invoke("text")
      }
    })

    it("contains complete VM setup", () => {
      cy.visit(href)
      cy.get(".detail-page-main > h2").eq(2).should("be.visible").should("include.text", "Setup")
      cy.get(".vm-details-two-column").eq(1).children().children().children().should("be.visible").invoke("text")
      cy.get(".vm-details-two-column").eq(2).children().children().children().children().should("be.visible").invoke("text")
      let detail_vms_types = ["ID", "Block device size", "Block device volume type",
        "Additional volumes", "Minimum", "Maximum", "Create concurrently"]
      for (let type of detail_vms_types) {
        cy.get(".vm-details-two-column").contains(type).should("be.visible").invoke("text")
      }
    })
  })
})
