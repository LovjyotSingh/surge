import { api } from '../api/client'

/** How long a background revoke may take before it is abandoned (the token still expires on its own). */
export const REVOKE_TIMEOUT_MS = 3000

/**
 * Asks the gateway to revoke `token` (`POST /api/auth/logout`) without making anyone wait: never throws, gives up
 * after {@link REVOKE_TIMEOUT_MS}. Used after the session has already moved on (logout, or a token replaced by a
 * verified one), so a failure changes nothing for the user.
 */
export function revokeInBackground(token: string): Promise<void> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REVOKE_TIMEOUT_MS)
  return api
    .logout(token, controller.signal)
    .catch(() => {
      // offline, timed out or already invalid: nothing to do
    })
    .finally(() => clearTimeout(timer))
}
