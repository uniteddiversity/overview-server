'use strict'

const asUserWithDocumentSet = require('../support/asUserWithDocumentSet')

describe('Plugins', function() {
  asUserWithDocumentSet('Metadata/basic.csv', function() {
    before(function() {
      this.browser.loadShortcuts('documentSet')
      this.browser.loadShortcuts('jquery')
      this.documentSet = this.browser.shortcuts.documentSet
    })

    it('should pass server, apiToken and documentSetId in the plugin query string', async function() {
      const server = await this.documentSet.createViewAndServer('show-query-string')

      try {
        await this.browser.switchToFrame('view-app-iframe')
        // Wait for load. This plugin is loaded when the <pre> is non-empty
        await this.browser.assertExists({ xpath: '//pre[text() and string-length()>0]', wait: true })
        const text = await this.browser.getText({ css: 'pre' })
        expect(text).to.match(/^\?server=http%3A%2F%2F[-\w.]+(?:%3A\d+)?&documentSetId=\d+&apiToken=[a-z0-9]+$/)
        await this.browser.switchToFrame(null)
      } finally {
        await server.close()
      }

      await this.documentSet.destroyView('show-query-string')
    })

    describe('with a plugin that calls setRightPane', async function() {
      before(async function() {
        this.server = await this.documentSet.createViewAndServer('right-pane')
      })

      after(async function() {
        await this.server.close()
        await this.documentSet.destroyView('right-pane')
      })

      it('should create a right pane', async function() {
        await this.browser.assertNotExists({ id: 'tree-app-vertical-split-2' }) // it's invisible

        // wait for load
        await this.browser.switchToFrame('view-app-iframe')
        await this.browser.assertExists({ css: 'body.loaded', wait: 'pageLoad' })
        await this.browser.click({ button: 'Set Right Pane' })
        await this.browser.switchToFrame(null)

        await this.browser.assertExists({ id: 'tree-app-vertical-split-2', wait: true }) // wait for animation
        await this.browser.click({ css: '#tree-app-vertical-split-2 button' })
        await browser.sleep(1000) // for animation
        await this.browser.assertExists({ id: 'view-app-right-pane-iframe' })
        await this.browser.switchToFrame('view-app-right-pane-iframe')
        const url = await this.browser.execute(function() { return window.location.href })
        expect(url).to.contain('?placement=right-pane')
        await this.browser.switchToFrame(null)

        // Move back to left
        await this.browser.click({ css: '#tree-app-vertical-split button' })
        await browser.sleep(1000) // for animation
      })
    })

    describe('with a plugin that calls setModalDialog', async function() {
      before(async function() {
        this.server = await this.documentSet.createViewAndServer('modal-dialog')
      })

      after(async function() {
        await this.server.close()
        await this.documentSet.destroyView('modal-dialog')
      })

      it('should create and close a modal dialog', async function() {
        const b = this.browser

        await b.assertNotExists({ id: 'view-app-modal-dialog' })

        // wait for load
        await b.switchToFrame('view-app-iframe')
        await b.assertExists({ css: 'body.loaded', wait: 'pageLoad' })
        await b.click({ button: 'Set Modal Dialog' })
        await b.switchToFrame(null)

        await b.assertExists({ id: 'view-app-modal-dialog', wait: true })

        await b.switchToFrame('view-app-modal-dialog-iframe')
        await b.click({ button: 'Set Modal Dialog to Null', wait: 'pageLoad' })
        await b.switchToFrame(null)

        await b.assertNotExists({ id: 'view-app-modal-dialog' })
      })

      it('should send messages from one plugin to another', async function() {
        const b = this.browser

        // wait for load
        await b.switchToFrame('view-app-iframe')
        await b.assertExists({ css: 'body.loaded', wait: 'pageLoad' })
        await b.click({ button: 'Set Modal Dialog' })
        await b.switchToFrame(null)

        await b.assertExists({ id: 'view-app-modal-dialog', wait: true })

        await b.switchToFrame('view-app-modal-dialog-iframe')
        await b.click({ button: 'Send Message', wait: 'pageLoad' })
        await b.click({ button: 'Set Modal Dialog to Null' })
        await b.switchToFrame(null)

        await b.switchToFrame('view-app-iframe')
        await b.assertExists({ tag: 'pre', contains: '{"This is":"a message"}', wait: true })
        await b.switchToFrame(null)

        await b.assertNotExists({ id: 'view-app-modal-dialog' })
      })
    })

    describe('with a plugin that calls setViewFilter', async function() {
      before(async function() {
        this.server = await this.documentSet.createViewAndServer('view-filter')
      })

      after(async function() {
        await this.server.close()
        await this.documentSet.destroyView('view-filter')
      })

      it('should allow filtering by view', async function() {
        const b = this.browser

        await b.click({ tag: 'a', contains: 'view-filter placeholder' })
        await b.click({ tag: 'span', contains: 'VF-Foo' })
        await this.documentSet.waitUntilDocumentListLoaded()
        expect(this.server.lastRequestUrl.path).to.match(/^\/filter\/01010101\?/)
        expect(this.server.lastRequestUrl.query.apiToken || '').to.match(/[a-z0-9]+/)
        expect(this.server.lastRequestUrl.query.ids).to.eq('foo')
        expect(this.server.lastRequestUrl.query.operation).to.eq('any')
        const text = await(b.getText('#document-list ul.documents'))
        expect(text).not.to.match(/First/)
        expect(text).to.match(/Second/)
        expect(text).not.to.match(/Third/)

        // reset
        await b.click('.view-filters a.nix')
        await this.documentSet.waitUntilDocumentListLoaded()
        const text2 = await(b.getText('#document-list ul.documents'))
        expect(text2).to.match(/First/)
      })

      it('should allow setViewFilterChoices', async function() {
        const b = this.browser

        await b.switchToFrame('view-app-iframe')
        await b.click({ button: 'setViewFilterChoices' })
        await b.switchToFrame(null)

        await b.click({ tag: 'a', contains: 'view-filter placeholder' })
        await b.click({ tag: 'span', contains: 'VF-Foo2' }) // assert it exists, really

        // reset
        await b.click('.view-filters a.nix')
        await this.documentSet.waitUntilDocumentListLoaded()
      })

      it('should allow setViewFilterSelection', async function() {
        const b = this.browser

        await b.switchToFrame('view-app-iframe')
        await b.click({ button: 'setViewFilterChoices' })
        await b.click({ button: 'setViewFilterSelection' })
        await b.sleep(100) // make sure postMessage() goes through. TODO notify from plugin?
        await b.switchToFrame(null)

        await this.documentSet.waitUntilDocumentListLoaded()
        expect(this.server.lastRequestUrl.path).to.match(/^\/filter\/01010101\?/) // the document list reloaded
        await b.assertExists({ tag: 'a', contains: 'VF-Foo2' }) // the tag was selected in the ViewFilter

        // reset
        await b.click('.view-filters a.nix')
        await this.documentSet.waitUntilDocumentListLoaded()
      })
    })

    describe('with a plugin that calls setDocumentDetailLink', function() {
      before(async function() {
        // open a document
        await this.browser.click({ tag: 'h3', contains: 'First' })
        await this.browser.sleep(1000) // wait for document to animate in
      })

      beforeEach(async function() {
        this.server = await this.documentSet.createViewAndServer('view-document-detail-links')
        this.clickViewButton = async function(name) {
          const b = this.browser
          await b.switchToFrame('view-app-iframe')
          await b.assertExists({ css: 'body.loaded', wait: 'pageLoad' })
          await b.click({ button: name })
          await b.switchToFrame(null)
        }
      })

      afterEach(async function() {
        await this.server.close()
        await this.documentSet.destroyView('document-detail-link')
      })

      it('should add the given link', async function() {
        const b = this.browser

        await this.clickViewButton("setUrl(foo)")
        await b.assertExists({ link: 'Text foo', wait: true })
      })

      it('should show the link even after page refresh', async function() {
        const b = this.browser

        await this.clickViewButton("setUrl(foo)")
        await b.assertExists({ link: 'Text foo', wait: true }) // wait for it to appear

        await b.refresh()
        await this.documentSet.waitUntilStable()
        // again, open a document
        await b.click({ tag: 'h3', contains: 'First' })
        await b.sleep(1000) // wait for document to animate in
        await b.assertExists({ link: 'Text foo', wait: true })
      })

      it('not create a duplicate link (to the same URL)', async function() {
        const b = this.browser

        await this.clickViewButton("setUrl(foo)")
        await b.assertExists({ link: 'Text foo', wait: true }) // wait for it to appear

        // waitUntilAjaxComplete: make sure we have 0 ajax requests (for next stuff)
        await b.shortcuts.jquery.listenForAjaxComplete()
        await this.documentSet.createCustomView('another view', `http://${this.server.hostname}:3333`)
        await b.shortcuts.jquery.waitUntilAjaxComplete()

        // Add the same URL again. Wait for it to complete. (That's an ajax request;
        // aren't you glad we're sure there were zero before?)
        await b.shortcuts.jquery.listenForAjaxComplete()
        await this.clickViewButton("setUrl(foo, foo with different text)")
        await b.shortcuts.jquery.waitUntilAjaxComplete()
        // Ajax is done. That means the new link has been saved to the server
        // and rendering is definitely finished. But since the URL is the same,
        // we expect nothing else to be rendered
        await b.assertNotExists({ link: 'foo with different text' })

        await this.documentSet.destroyView('another view') // cleanup
      })

      it('should open the popup', async function() {
        const b = this.browser

        await this.clickViewButton("setUrl(foo)")
        await b.click({ link: 'Text foo', wait: true }) // wait for it to appear

        await b.assertExists({ css: 'iframe#view-document-detail', wait: 'fast' }) // wait for iframe
        await b.switchToFrame('view-document-detail')
        const url = await this.browser.execute(function() { return window.location.href })
        await b.switchToFrame(null)
        expect(url).to.match(/\?documentId=\d+/)
        expect(url).to.match(/&foo=foo/)
      })
    })
  })
})
