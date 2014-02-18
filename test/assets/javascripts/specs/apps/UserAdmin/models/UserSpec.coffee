define [ 'backbone', 'apps/UserAdmin/models/User' ], (Backbone, User) ->
  describe 'apps/UserAdmin/models/User', ->
    subject = undefined

    beforeEach ->
      subject = new User
        email: 'user@example.org'
        is_admin: false
        confirmed_at: '2014-02-17T20:48:50Z'
        last_sign_in_at: '2014-02-17T20:48:50Z'
        last_activity_at: null

    it 'should parse a date', -> expect(subject.getDate('confirmed_at').getTime()).toEqual(1392670130000)
    it 'should not parse a null date', -> expect(subject.getDate('last_activity_at')).toBe(null)

    describe 'a new User', ->
      it 'should have isNew=true', -> expect(subject.isNew()).toBe(true)
      it 'should have a collection URL', -> expect(subject.url()).toEqual('/admin/users')

    describe 'an existing User', ->
      beforeEach -> subject.set(id: 1)
      it 'should have isNew=false', -> expect(subject.isNew()).toBe(false)
      it 'should have a model URL', -> expect(subject.url()).toEqual('/admin/users/user%40example.org')
