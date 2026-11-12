import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import Countdown from './Countdown'

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-12T10:00:00Z'))
})
afterEach(() => {
  vi.useRealTimers()
})

const inSeconds = (s: number) => new Date(Date.now() + s * 1000).toISOString()

it('counts down and fires onDone once', () => {
  const onDone = vi.fn()
  render(<Countdown target={inSeconds(65)} onDone={onDone} />)
  expect(screen.getByText('01:05')).toBeInTheDocument()
  act(() => {
    vi.advanceTimersByTime(65_000)
  })
  expect(screen.getByText('00:00')).toBeInTheDocument()
  expect(onDone).toHaveBeenCalledTimes(1)
  act(() => {
    vi.advanceTimersByTime(5_000)
  })
  expect(onDone).toHaveBeenCalledTimes(1)
})

it('shows hours when long', () => {
  render(<Countdown target={inSeconds(3700)} onDone={() => {}} />)
  expect(screen.getByText('1:01:40')).toBeInTheDocument()
})
