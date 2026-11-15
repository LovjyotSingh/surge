import { afterEach, expect, it } from 'vitest'
import { clearSession, getSession, setSession } from './session'
import { signIn, user } from '../test-utils'

afterEach(() => {
  clearSession()
  sessionStorage.clear()
})

it('stores the session under surge.session', () => {
  signIn(['USER'], 60_000, 'abc')
  const raw = JSON.parse(sessionStorage.getItem('surge.session') as string)
  expect(raw.token).toBe('abc')
  expect(raw.user).toEqual(user())
  expect(typeof raw.expiresAt).toBe('string')
  expect(getSession()?.token).toBe('abc')
})

it('treats an expired session as logged out and removes it', () => {
  signIn(['USER'], -1000)
  expect(getSession()).toBeNull()
  expect(sessionStorage.getItem('surge.session')).toBeNull()
})

it('treats garbage in storage as logged out', () => {
  sessionStorage.setItem('surge.session', '{not json')
  expect(getSession()).toBeNull()
  sessionStorage.setItem('surge.session', JSON.stringify({ token: 1 }))
  expect(getSession()).toBeNull()
})

it('clearSession logs out', () => {
  setSession({ token: 't', expiresAt: new Date(Date.now() + 60_000).toISOString(), user: user() })
  clearSession()
  expect(getSession()).toBeNull()
})
