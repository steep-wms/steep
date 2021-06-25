
describe('Services', () => {
    beforeEach(() => {
        cy.visit('/services/')
    })
    it('Title', () => {
        cy.get('h1').should("be.visible").should('have.text', 'Services')
    })

    it('Service Items', () => {
        cy.get('.list-item').each(($el, index, $list) => {
            cy.wrap($el).find('.list-item-left > .list-item-title > a').should("be.visible").invoke('text')
            cy.wrap($el).find('.list-item-left > .list-item-title > a').should('have.attr', 'href')
            cy.wrap($el).find('.list-item-left > .list-item-title > span').should("be.visible").invoke('text')
            cy.wrap($el).find('.list-item-left > .list-item-subtitle').should("be.visible").invoke('text')
        })
    })

    it.only('Service Items Page', () => {
        let item_href = []
        cy.get('.list-item').each(($el, index, $list) => {
            cy.wrap($el).find('.list-item-left > .list-item-title > a').invoke('attr', 'href').then((href) => {
                item_href.push(href)
                cy.log(href)
            })
        }).then(() => {
            for (let href of item_href) {
                cy.log(href)
                cy.visit(href)
                cy.get('.detail-page-title > h1').should("be.visible").invoke('text')
                cy.get('.detail-page-subtitle').should("be.visible").invoke('text')

                // service details
                cy.get('.service-details-left > :nth-child(1)').should("be.visible").should('have.text', 'ID')
                cy.get('.service-details-left > :nth-child(2)').should("be.visible").invoke('text')
                cy.get('.service-details-left > :nth-child(3)').should("be.visible").should('have.text', 'Required capabilities')
                cy.get('.service-details-left > :nth-child(4)').should("be.visible").invoke('text')
                cy.get('.service-details-right > :nth-child(1)').should("be.visible").should('have.text', 'Runtime')
                cy.get('.service-details-right > :nth-child(2)').should("be.visible").invoke('text')
                cy.get('.service-details-right > :nth-child(3)').should("be.visible").invoke('text')
                cy.get('.service-details-right > :nth-child(4)').should("be.visible").invoke('text')

                // parameters
                if (cy.get('.detail-page-main').children().length > 1){
                    cy.get('h2').should("be.visible").should('have.text', 'Parameters').then(() => {
                        cy.get('.service-parameter').each(($el, index, $list) => {
                            cy.wrap($el).find('.service-parameter-left').children().should('have.length', 1)
                            cy.wrap($el).find('.service-parameter-right').children().should('have.length', 14).should("be.visible")
                            cy.wrap($el).find('.service-parameter-left').should("be.visible").invoke('text')
                            let types = ['ID:', 'Type:', 'Data type:', 'Default value:', 'File suffix:', 'Label:']
                            for (type of types) {
                                cy.wrap($el).find('.service-parameter-right').contains(type).should("be.visible")
                            }
                        })
                    })
                }
            }
        })
    })
})
