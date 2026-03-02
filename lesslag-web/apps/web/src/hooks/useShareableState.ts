'use client';

import { useEffect, useRef } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';

/**
 * Hook that syncs preset state to URL hash for shareable links.
 * Encodes: profile, tier, aggressiveness, playerCount, activeTab
 * Format: #profile=SURVIVAL&tier=MID&agg=MODERATE&players=50&tab=presets
 */
export function useShareableState() {
  const initialized = useRef(false);
  const { profile, tier, aggressiveness, playerCount, activeTab } = useLessLagStore();
  const { setProfile, setTier, setAggressiveness, setPlayerCount, setActiveTab } = useLessLagStore();

  // Read from URL on mount
  useEffect(() => {
    if (initialized.current) return;
    initialized.current = true;

    const hash = window.location.hash.slice(1);
    if (!hash) return;

    const params = new URLSearchParams(hash);

    const p = params.get('profile');
    if (p) setProfile(p as typeof profile);

    const t = params.get('tier');
    if (t) setTier(t as typeof tier);

    const a = params.get('agg');
    if (a) setAggressiveness(a as typeof aggressiveness);

    const pl = params.get('players');
    if (pl) setPlayerCount(Number(pl));

    const tab = params.get('tab');
    if (tab) setActiveTab(tab as typeof activeTab);
  }, [setProfile, setTier, setAggressiveness, setPlayerCount, setActiveTab]);

  // Write to URL on state change
  useEffect(() => {
    if (!initialized.current) return;

    const params = new URLSearchParams();
    params.set('profile', profile);
    params.set('tier', tier);
    params.set('agg', aggressiveness);
    params.set('players', String(playerCount));
    params.set('tab', activeTab);

    window.history.replaceState(null, '', `#${params.toString()}`);
  }, [profile, tier, aggressiveness, playerCount, activeTab]);
}

/**
 * Generate a shareable URL for the current state.
 */
export function generateShareUrl(): string {
  const s = useLessLagStore.getState();
  const params = new URLSearchParams();
  params.set('profile', s.profile);
  params.set('tier', s.tier);
  params.set('agg', s.aggressiveness);
  params.set('players', String(s.playerCount));
  params.set('tab', s.activeTab);

  return `${window.location.origin}${window.location.pathname}#${params.toString()}`;
}
