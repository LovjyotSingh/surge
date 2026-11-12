import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import SeatMap from './SeatMap'
import type { SeatView } from '../api/types'

const seat = (id: number, over: Partial<SeatView> = {}): SeatView => ({
  id,
  rowLabel: 'A',
  number: id,
  tier: 'PLATINUM',
  basePriceInr: 4999,
  state: 'AVAILABLE',
  ...over,
})

it('disables held and sold seats', () => {
  render(<SeatMap seats={[seat(1), seat(2, { state: 'HELD' }), seat(3, { state: 'SOLD' })]} selected={new Set()} onToggle={() => {}} />)
  const buttons = screen.getAllByRole('button')
  expect(buttons[0]).toBeEnabled()
  expect(buttons[1]).toBeDisabled()
  expect(buttons[2]).toBeDisabled()
})

it('limits selection to 8', async () => {
  const seats = Array.from({ length: 9 }, (_, i) => seat(i + 1))
  const onToggle = vi.fn()
  render(<SeatMap seats={seats} selected={new Set([1, 2, 3, 4, 5, 6, 7, 8])} onToggle={onToggle} />)
  await userEvent.click(screen.getByRole('button', { name: /Row A seat 9,/ }))
  expect(onToggle).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: /Row A seat 8,/ }))
  expect(onToggle).toHaveBeenCalledWith(8)
})

it('labels seats for screen readers', () => {
  render(<SeatMap seats={[seat(1)]} selected={new Set()} onToggle={() => {}} />)
  expect(screen.getByRole('button', { name: /Row A seat 1, PLATINUM, ₹4999, available/ })).toBeInTheDocument()
})

it('marks selected seats as pressed', () => {
  render(<SeatMap seats={[seat(1), seat(2)]} selected={new Set([1])} onToggle={() => {}} />)
  expect(screen.getByRole('button', { name: /seat 1,/ })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: /seat 2,/ })).toHaveAttribute('aria-pressed', 'false')
})
