import { LocalStore } from './localStore'
import { SupabaseStore } from './supabaseStore'
import type { Store } from './types'

/** Supabase when both keys are set, otherwise the browser alone. */
export function createStore(): Store {
  const url = import.meta.env.VITE_SUPABASE_URL as string | undefined
  const key = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined
  if (url && key) return new SupabaseStore(url.replace(/\/$/, ''), key)
  return new LocalStore()
}
