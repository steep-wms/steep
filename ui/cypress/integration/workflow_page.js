
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

describe('Generall UI', () => {
    before(() => {
        cy.visit('/workflows')
    })
    it('can access Filter Dropdown Menu', () => {
        const options = ['Failed workflows only', 'Partially succeeded workflows only', 'Running workflows only']
        const params = ['/?status=ERROR', '/?status=PARTIAL_SUCCESS', '/?status=RUNNING']
        cy.get('.dropdown-btn > span').contains('Filter')
        cy.get('.dropdown-btn').should('be.visible')
        for(let i in options) {
            cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).should('not.be.visible')
        }

        cy.get('.dropdown-btn').click()

        for(let i in options){
            cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).should('be.visible')
            cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).should('have.text', options[i])
        }

        for(let i in options){
            cy.get(`.dropdown-menu > ul > :nth-child(${parseInt(i) + 1})`).click()
            cy.url().should('include', params[i])
            cy.get('.dropdown-btn').click()
        }
        cy.get('.dropdown-btn').click()
    })
})

describe('Workflow Page Successfully Done', () => {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            res = response
            cy.visit('/workflows')
        })
    })

    it('has Listitem with correct name', () => {
        cy.contains(res.body.id)
    })

    it('Listitem has correct redirecting Done', () => {
        cy.contains(res.body.id).click()
        cy.url().should('include', `/workflows/${res.body.id}/`)
    })
})

describe('Workflow Page Cancelling', () => {
    var res
    before(() => {
        cy.request('POST', '/workflows', payload).then((response) => {
            // refresh
            res = response
            cy.visit('/workflows')
        })
    })

    it('Cancels an Exisiting Workflow', () => {
        cy.visit(`/workflows/${res.body.id}/`)
        cy.contains(res.body.id).click()
        cy.get('.dropdown-btn').click()
        cy.get('li').click()
        cy.get('.btn-error').click()
        cy.visit('/workflows')
        cy.get('.list-page').contains(res.body.id).parentsUntil('.list-page').contains('Cancelling')
        cy.get('.list-page').contains(res.body.id).parentsUntil('.list-page').contains('1 of 1 completed')
        cy.get('.list-page').contains(res.body.id).parentsUntil('.list-page').contains('Cancelled')
    })
})

describe('has Table', () => {
    var res = []
    before(() => {
        for (let i = 0; i < 20; i++) {
            cy.request('POST', '/workflows', payload).then((response) => {
                res.unshift(response)
                cy.wait(50)
                
            })
        }
    })

    beforeEach(() => {
        cy.visit('workflows')
    })

    it('shows all items', () => {
        for (let i = 2; i <= 11; i++) {
            cy.get(`.list-page > :nth-child(${i}) > .list-item-left > .list-item-title > a`, { timeout: 10000}).should('have.text', res[i - 2].body.id)
        }
        cy.get('.pagination > :last').click()
        for (let i = 2; i <= 11; i++) {
            cy.get(`.list-page > :nth-child(${i}) > .list-item-left > .list-item-title > a`, { timeout: 10000}).should('have.text', res[i - 2 + 10].body.id)
        }
    })

    it('has time dialog box', () => {
        for (let i = 2; i <= 11; i++) {
            cy.get(`:nth-child(${i}) > .list-item-left > .list-item-subtitle`).should('be.visible')
            cy.get(`.list-page > :nth-child(${i}) > .list-item-left > .list-item-subtitle > .tooltip`, { timeout: 10000}).should('be.not.visible')
            cy.get(`.list-page > :nth-child(${i}) > .list-item-left > .list-item-subtitle > span > time`, { timeout: 10000}).trigger('mouseover')
            cy.get(`.list-page > :nth-child(${i}) > .list-item-left > .list-item-subtitle > .tooltip`, { timeout: 10000}).should('be.visible')
        }
    })

    it('shows "running" workflows', () => {
        cy.get('.dropdown-btn').click()
        cy.get('.dropdown-menu > ul > :nth-child(3)').click()
        cy.wait(2000)
        cy.get('.list-page').children().each(($el, index, $list) => {
            if(index != 0 && index != ($list.length - 1)){
                cy.wrap($el).contains('Running')
                cy.wrap($el).get('.list-item-right > .list-item-progress-box > .feather.running').should('be.visible')
            }
        })
    })

})
