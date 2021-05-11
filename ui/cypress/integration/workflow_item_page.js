
const timeoutOffset = 5
const timeoutLength = 5
const numOfActions = 1
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

describe('Workflow Item Page Attributes are not hidden', () => {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
        })
    })

    it('has correct Header', () => {
        cy.get('.detail-page-title > h1').should('have.text', res.body.id)
        cy.get('.detail-page-title > h1').should('be.visible')
        cy.get('.breadcrumbs > :nth-child(2)').should('have.text', res.body.id)
        cy.get('.breadcrumbs > :nth-child(2)').should('be.visible')
        cy.get('.breadcrumbs > :nth-child(1) > a').should('have.text', 'Workflows')
        cy.get('.breadcrumbs > :nth-child(1) > a').should('be.visible')
        cy.get('.breadcrumbs > :nth-child(1) > a').should('be.visible').invoke('attr', 'href').should('include', '/workflows/')
    })

    it('can access Actions Combobox', () => {
        cy.get('.dropdown-btn').should('have.text', 'Actions ')
        cy.get('.dropdown-btn').click()
        cy.get('li').should('have.text', 'Cancel')
        cy.get('li').click()
        cy.get('.cancel-modal-buttons > :nth-child(1)').should('have.text', 'Keep it')
        cy.get('.cancel-modal-buttons > :nth-child(1)').click()
        cy.get('.list-item-progress-box > div > strong').contains("Running")
    })

    it('can access Start time', () => {
        cy.get('.definition-list > :nth-child(1)').should('have.text', 'Start time')
        cy.get('.definition-list > :nth-child(1)').should('be.visible')
    })

    it('can access actual Start time', () => {
        cy.get('.definition-list > :nth-child(2)').should('have.not.text', '–')
        cy.get('.definition-list > :nth-child(2)').should('be.visible')
    })

    it('can access End time', () => {
        cy.get('.definition-list > :nth-child(3)').should('have.text', 'End time')
        cy.get('.definition-list > :nth-child(3)').should('be.visible')
    })

    it('can access actual End time', () => {
        cy.get('.definition-list > :nth-child(4)').should('have.text', '–')
        cy.get('.definition-list > :nth-child(4)').should('be.visible')
    })

    it('can access Time elapsed', () => {
        cy.get('.definition-list > :nth-child(5)').should('have.text', 'Time elapsed')
        cy.get('.definition-list > :nth-child(5)').should('be.visible')
    })

    it('can access actual Time elapsed', () => {
        cy.get('.definition-list > :nth-child(6)').should('have.not.text', '–')
        cy.get('.definition-list > :nth-child(6)').should('be.visible')
    })

    it('can access Required capabilities', () => {
        cy.get('.definition-list > :nth-child(7)').should('have.text', 'Required capabilities')
        cy.get('.definition-list > :nth-child(7)').should('be.visible')
    })

    it('can access actual Required capabilities', () => {
        cy.get('.definition-list > :nth-child(8)').should('be.visible')
    })

    it('can access actual Required capabilities', () => {
        cy.get('.code-box-title > :nth-child(2)').should('be.visible')
    })

    it('can access Source Tab JSON', () => {
        cy.get('.code-box-title > :nth-child(2)').click()
        cy.get('.code-box-tab.active').should('be.visible')
    })

    it('can access Source', () => {
        cy.get('h2').should('have.text', 'Source')
        cy.get('h2').should('be.visible')
    })

    it('can access Source Tab YAML', () => {
        cy.get('.code-box-title > :nth-child(1)').click()
        cy.get('.code-box-tab.active').should('be.visible')
    })

})

describe('Workflow Item Page Successfully Done', () => {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
        })
    })

    it('has correct title', () => {
        cy.get('.detail-page-title > h1').contains(res.body.id)
    })

    it('has correct running flags', () => {
        cy.get('.list-item-progress-box > div > strong').contains("Running")
        cy.get('.list-item-progress-box > div > strong').contains("Success", { timeout: (timeoutLength + timeoutOffset) * 1000 })
    })
})

describe('Resubmission', ()=> {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
        })
    })
    it('can resubmit', () => {
        cy.get('.list-item-progress-box > div > strong', { timeout: (timeoutLength + timeoutOffset) * 1000 }).should('have.text', 'Success')
        cy.get('.dropdown-btn').click()
        cy.get('li').click()
        cy.wait(1000)
        cy.get('.buttons > .primary').click()

        cy.get('.list-item-progress-box > div > strong').should('have.text', `${numOfActions} Running`)
        cy.get('.list-item-progress-box > div > a').should('have.text', `0 of ${numOfActions} completed`)
        
        cy.get('.list-item-progress-box > div > strong', { timeout: (timeoutLength + timeoutOffset) * 1000 }).should('have.text', '0 Running')
        cy.get('.list-item-progress-box > div > a', { timeout: (timeoutLength + timeoutOffset) * 1000 }).should('have.text', `${numOfActions} of ${numOfActions} completed`)

        cy.get('.list-item-progress-box > div > strong', { timeout: (timeoutLength + timeoutOffset) * 1000 }).should('have.text', 'Success')
        cy.get('.list-item-progress-box > div > a').should('have.text', `${numOfActions} completed`)
    })
})

describe('Workflow Item Page Cancelling', () => {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
        })
    })

    it('cancels an exisiting workflow', () => {
        cy.get('.dropdown-btn').should('have.text', 'Actions ')
        cy.get('.dropdown-btn').click()
        cy.get('li').should('have.text', 'Cancel')
        cy.get('li').click()
        cy.get('.btn-error').should('have.text', 'Cancel it now')
        cy.get('.btn-error').click()
        cy.get('.list-item-progress-box > div > a').should('have.text', `${numOfActions} of ${numOfActions} completed`)
        cy.get('.list-item-progress-box > div > strong').should('have.text', 'Cancelled')
        cy.get('.list-item-progress-box > div > a').should('have.text', `${numOfActions} completed`)
    })
})

describe('Check Times elapsed', () => {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            res = response
            cy.visit(`/workflows/${response.body.id}/`)
            cy.wait(50)
        })
    })

    it('time elapsed features is working', () => {
        cy.get('.definition-list > :nth-child(6)').should('have.text', '1s')
        cy.wait(1000)
        cy.get('.definition-list > :nth-child(6)').should('have.text', '2s')
        cy.wait(1000)
        cy.get('.definition-list > :nth-child(6)').should('have.text', '3s')
        cy.wait(1000)
        cy.get('.definition-list > :nth-child(6)').should('have.text', '4s')
        cy.wait(1000)
    })
})