import { StrictMode } from 'react'
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

it('stops ticking after unmount', () => {
  const onDone = vi.fn()
  const { unmount } = render(<Countdown target={inSeconds(10)} onDone={onDone} />)
  unmount()
  expect(vi.getTimerCount()).toBe(0)
  act(() => {
    vi.advanceTimersByTime(20_000)
  })
  expect(onDone).not.toHaveBeenCalled()
})

it('does not restart or refire when onDone identity changes, and calls the latest', () => {
  const first = vi.fn()
  const second = vi.fn()
  const target = inSeconds(10)
  const { rerender } = render(<Countdown target={target} onDone={first} />)
  act(() => {
    vi.advanceTimersByTime(4_000)
  })
  rerender(<Countdown target={target} onDone={second} />)
  expect(screen.getByText('00:06')).toBeInTheDocument()
  expect(first).not.toHaveBeenCalled()
  act(() => {
    vi.advanceTimersByTime(6_000)
  })
  expect(first).not.toHaveBeenCalled()
  expect(second).toHaveBeenCalledTimes(1)
})

it('fires onDone exactly once for an already-expired target under StrictMode', () => {
  const onDone = vi.fn()
  render(
    <StrictMode>
      <Countdown target={inSeconds(-30)} onDone={onDone} />
    </StrictMode>,
  )
  expect(screen.getByText('00:00')).toBeInTheDocument()
  act(() => {
    vi.advanceTimersByTime(2_000)
  })
  expect(onDone).toHaveBeenCalledTimes(1)
})
