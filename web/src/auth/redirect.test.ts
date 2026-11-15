import { expect, it } from 'vitest'
import { safeNext } from './redirect'

it.each([
  '/\t/evil.example',
  '/\n/evil',
  '/\\evil',
  '\\\\evil',
  '//evil',
  'https://evil',
  'javascript:alert(1)',
  '',
  null,
])('rejects %j', (raw) => {
  expect(safeNext(raw as string | null)).toBe('/')
})

it('rejects values decoded from the query string', () => {
  for (const q of ['%2F%09%2Fevil.example', '/%09/evil.example', '/%0A/evil', '/%5Cevil', '%5C%5Cevil']) {
    const next = new URLSearchParams(`next=${q}`).get('next')
    expect(safeNext(next)).toBe('/')
  }
})

it('keeps local paths', () => {
  expect(safeNext('/events/1')).toBe('/events/1')
  expect(safeNext('/tickets/5?x=1#a')).toBe('/tickets/5?x=1#a')
})
