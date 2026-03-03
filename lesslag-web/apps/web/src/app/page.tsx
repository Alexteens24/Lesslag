'use client';

import { useEffect, useState } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';
import type { SetupStepId } from '@/store/lesslag-store';
import { PresetSelector } from '@/components/PresetSelector';
import { ConfigEditor } from '@/components/ConfigEditor';
import { DiffViewer } from '@/components/DiffViewer';
import { RationalePanel } from '@/components/RationalePanel';
import { ImportExportModal } from '@/components/ImportExportModal';
import { HardwareWizard } from '@/components/HardwareWizard';
import { DetectedHardwareCard } from '@/components/DetectedHardwareCard';
import { PluginConflictDetector } from '@/components/PluginConflictDetector';
import { SnapshotManager } from '@/components/SnapshotManager';
import { Sidebar } from '@/components/Sidebar';
import { ToastStack } from '@/components/ui/Toast';
import { useShareableState, generateShareUrl } from '@/hooks/useShareableState';
import { decodeServerPayload } from '@/lib/decode-payload';

// ── Adaptive step display ────────────────────────────────────────────────
// When a server payload is present, titles/descriptions reflect real data.
type StepId = 'hardware' | 'preset' | 'analysis' | 'changes' | 'export';
type StepDisplay = { title: string; description: string; primaryLabel: string };

function getStepDisplay(
  step: { id: StepId; title: string; description: string; primaryLabel: string },
  serverPayload: { fork: string; mcVersion: string; javaVersion: number; maxHeapMb: number; tps: number; mspt: number; cpuModel: string } | null,
): StepDisplay {
  if (!serverPayload) return step;
  const { fork, mcVersion, javaVersion, maxHeapMb, tps, mspt } = serverPayload;
  const heapGb = Math.round(maxHeapMb / 1024);
  switch (step.id) {
    case 'hardware':
      return {
        title: 'Detected Server',
        description:
          `${fork} ${mcVersion} · Java ${javaVersion} · ${heapGb} GB heap ` +
          `· TPS ${tps.toFixed(1)} · ${mspt.toFixed(0)} ms MSPT — detected from your plugin link.`,
        primaryLabel: 'Confirm & Continue',
      };
    case 'preset':
      return {
        title: 'Optimisation Profile',
        description:
          'Choose your server type and aggressiveness — hardware tier was detected automatically from the plugin.',
        primaryLabel: step.primaryLabel,
      };
    case 'analysis':
      return {
        title: 'Analyse Configuration',
        description: `Reviewing rule engine findings for your ${fork} ${mcVersion} configuration.`,
        primaryLabel: step.primaryLabel,
      };
    default:
      return step;
  }
}

const SETUP_STEPS = [
  {
    id: 'hardware' as SetupStepId,
    title: 'Hardware Baseline',
    description: 'Configure your hosting environment — CPU, RAM, server fork, and Minecraft version.',
    tab: 'hardware' as const,
    primaryLabel: 'Save Hardware',
  },
  {
    id: 'preset' as SetupStepId,
    title: 'Profile & Preset',
    description: 'Choose your game profile, hardware tier, and how aggressively to optimise.',
    tab: 'presets' as const,
    primaryLabel: 'Generate Recommendations',
  },
  {
    id: 'analysis' as SetupStepId,
    title: 'Analyse Configuration',
    description: 'Review AI-powered findings for your current config values.',
    tab: 'rationale' as const,
    primaryLabel: 'Confirm & Continue',
  },
  {
    id: 'changes' as SetupStepId,
    title: 'Review Proposed Changes',
    description: 'Inspect the generated diffs, select which ones to apply, and commit them.',
    tab: 'diff' as const,
    primaryLabel: 'Apply Selected Changes',
  },
  {
    id: 'export' as SetupStepId,
    title: 'Finalise & Export',
    description: 'Download your optimised config files or snapshot for rollback.',
    tab: 'editor' as const,
    primaryLabel: 'Export / Share',
  },
] as const;

const SETUP_TABS = new Set<string>(SETUP_STEPS.map((s) => s.tab));

const API_URL =
  process.env.NEXT_PUBLIC_API_URL ??
  'https://lesslag-api.daucatmoitu.workers.dev';

const LAST_CLOUD_SESSION_KEY = 'lesslag:last-cloud-session-url';

export default function HomePage() {
  const {
    activeTab,
    setActiveTab,
    evaluation,
    diffs,
    selectedProposals,
    snapshots,
    setShowImportModal,
    profile,
    tier,
    aggressiveness,
    playerCount,
    plugins,
    hardware,
    platform,
    configs,
    runEvaluation,
    generatePresetAction,
    applySelectedProposals,
    setupProgress,
    completeSetupStep,
    setSetupStepReady,
    addToast,
  } = useLessLagStore();

  const [isCreatingCloudLink, setIsCreatingCloudLink] = useState(false);
  const [lastCloudSessionUrl, setLastCloudSessionUrl] = useState<string | null>(null);
  const [showManualHardware, setShowManualHardware] = useState(false);

  const serverPayload = useLessLagStore((s) => s.serverPayload);
  const setServerPayload = useLessLagStore((s) => s.setServerPayload);
  const setBenchmarkResult = useLessLagStore((s) => s.setBenchmarkResult);
  const buildExportArtifacts = useLessLagStore((s) => s.buildExportArtifacts);

  useShareableState();

  // ── URL payload hydration ────────────────────────────────────────────
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const params = new URLSearchParams(window.location.search);
    const token = params.get('s');
    if (!token) return;

    (async () => {
      const payload = await decodeServerPayload(token);
      if (!payload) {
        addToast('Failed to decode server payload from URL.', 'error');
        return;
      }
      setServerPayload(payload);
      completeSetupStep('hardware');
      setSetupStepReady('preset');
      addToast('Server hardware detected from link — step 1 auto-completed.', 'success');

      // Resolve benchmark score
      try {
        const res = await fetch(
          `${API_URL}/api/benchmarks/search?q=${encodeURIComponent(payload.cpuModel)}`,
        );
        if (res.ok) {
          const data = (await res.json()) as {
            results: { model: string; score: number; tier: string; similarity: number }[];
          };
          if (data.results.length > 0) {
            const best = data.results[0];
            setBenchmarkResult(best.score, best.tier as 'LOW' | 'MID' | 'HIGH');
            addToast(`CPU matched: ${best.model} (${best.score} pts, ${best.tier})`, 'info');
          }
        }
      } catch {
        // Non-critical: benchmark lookup failure
      }

      // Clean up URL
      window.history.replaceState({}, '', window.location.pathname);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const url = window.localStorage.getItem(LAST_CLOUD_SESSION_KEY);
    if (url) setLastCloudSessionUrl(url);
  }, []);

  // ── Derived state ──────────────────────────────────────────────────────

  const isSetupTab = SETUP_TABS.has(activeTab);
  const activeStepIndex = Math.max(0, SETUP_STEPS.findIndex((s) => s.tab === activeTab));
  const currentStep = SETUP_STEPS[activeStepIndex];

  // ── Handlers ───────────────────────────────────────────────────────────

  const goToStep = (index: number) => {
    const bounded = Math.max(0, Math.min(index, SETUP_STEPS.length - 1));
    setActiveTab(SETUP_STEPS[bounded].tab);
  };

  const handleStepPrimaryAction = () => {
    switch (currentStep.id) {
      case 'hardware': {
        completeSetupStep('hardware');
        setSetupStepReady('preset');
        addToast('Hardware baseline saved.', 'success');
        goToStep(1);
        return;
      }
      case 'preset': {
        generatePresetAction();
        runEvaluation();
        setSetupStepReady('analysis');
        addToast('Recommendations generated — review the analysis.', 'success');
        goToStep(2);
        return;
      }
      case 'analysis': {
        if (!evaluation) runEvaluation();
        completeSetupStep('analysis');
        setSetupStepReady('changes');
        addToast('Analysis confirmed — review proposed changes.', 'success');
        goToStep(3);
        return;
      }
      case 'changes': {
        if (diffs.length === 0) {
          addToast('No changes yet — generate recommendations first.', 'warning');
          return;
        }
        if (selectedProposals.size === 0) {
          addToast('Select at least one change before applying.', 'warning');
          return;
        }
        const n = selectedProposals.size;
        applySelectedProposals();
        completeSetupStep('changes');
        setSetupStepReady('export');
        addToast(`Applied ${n} change${n > 1 ? 's' : ''} to your config.`, 'success');
        goToStep(4);
        return;
      }
      case 'export': {
        buildExportArtifacts();
        completeSetupStep('export');
        setShowImportModal(true);
        return;
      }
    }
  };

  const handlePreviousStep = () => {
    if (activeStepIndex <= 0) return;
    goToStep(activeStepIndex - 1);
  };

  const handleShare = () => {
    const url = generateShareUrl();
    navigator.clipboard.writeText(url);
    addToast('Local share link copied to clipboard.', 'success');
  };

  const handleCreateCloudLink = async () => {
    if (isCreatingCloudLink) return;
    setIsCreatingCloudLink(true);
    addToast('Creating cloud session…', 'info');
    try {
      const res = await fetch(`${API_URL}/api/sessions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          profile, tier, aggressiveness, playerCount,
          plugins, hardware, platform, configs,
          serverName: 'LessLag Configurator Session',
        }),
      });
      const body = (await res.json()) as { url?: string; token?: string; message?: string };
      if (!res.ok) throw new Error(body.message ?? `Error ${res.status}`);
      const url =
        body.url ?? (body.token ? `${window.location.origin}/session/${body.token}` : null);
      if (!url) throw new Error('API did not return a session URL.');
      await navigator.clipboard.writeText(url);
      window.localStorage.setItem(LAST_CLOUD_SESSION_KEY, url);
      setLastCloudSessionUrl(url);
      addToast('Cloud session link copied to clipboard.', 'success');
    } catch (err) {
      addToast(err instanceof Error ? err.message : 'Failed to create cloud link.', 'error');
    } finally {
      setIsCreatingCloudLink(false);
    }
  };

  const isChangesCTADisabled =
    currentStep.id === 'changes' && (diffs.length === 0 || selectedProposals.size === 0);

  const stepDisplay = getStepDisplay(
    currentStep as Parameters<typeof getStepDisplay>[0],
    serverPayload,
  );

  // ── Render ────────────────────────────────────────────────────────────

  return (
    <>
      {/* ── Mobile horizontal step strip ─────────────────────────────── */}
      <div className="mb-4 overflow-x-auto md:hidden">
        <div className="flex items-center gap-1.5 pb-1 min-w-max">
          {SETUP_STEPS.map((step, i) => {
            const status = setupProgress[step.id];
            const isActive = step.tab === activeTab;
            return (
              <button
                key={step.id}
                onClick={() => goToStep(i)}
                className={`flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-xs font-medium whitespace-nowrap transition-colors ${isActive
                    ? 'border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--accent)]'
                    : status === 'done'
                      ? 'border-[var(--success)]/40 bg-[var(--success)]/5 text-[var(--success)]'
                      : status === 'ready'
                        ? 'border-[var(--accent)]/30 text-[var(--text-secondary)]'
                        : 'border-[var(--border)] text-[var(--text-muted)]'
                  }`}
              >
                <span>{status === 'done' && !isActive ? '✓' : i + 1}</span>
                <span>{step.title}</span>
              </button>
            );
          })}
          <button
            onClick={() => setActiveTab('conflicts')}
            className={`flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-xs font-medium whitespace-nowrap transition-colors ${activeTab === 'conflicts'
                ? 'border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-muted)]'
              }`}
          >
            🔌 Plugins
          </button>
        </div>
      </div>

      {/* ── Main layout ──────────────────────────────────────────────── */}
      <div className="flex gap-5 items-start">

        {/* Desktop sidebar */}
        <Sidebar
          onShare={handleShare}
          onCloudLink={handleCreateCloudLink}
          isCreatingCloudLink={isCreatingCloudLink}
          lastCloudSessionUrl={lastCloudSessionUrl}
        />

        {/* Content column */}
        <div className="flex-1 min-w-0 space-y-5">

          {/* Step / page header */}
          <div>
            {isSetupTab ? (
              <>
                <h2 className="text-lg font-semibold text-[var(--text-primary)] sm:text-xl">
                  {stepDisplay.title}
                </h2>
                <p className="mt-0.5 text-sm text-[var(--text-muted)]">
                  {stepDisplay.description}
                </p>
              </>
            ) : (
              <>
                <h2 className="text-lg font-semibold text-[var(--text-primary)] sm:text-xl">
                  Plugin Checker
                </h2>
                <p className="mt-0.5 text-sm text-[var(--text-muted)]">
                  Check your plugin list for known conflicts with LessLag.
                </p>
              </>
            )}
          </div>

          {/* Tab content */}
          <div className="min-h-[55vh]">
            {activeTab === 'presets' && <PresetSelector />}
            {activeTab === 'editor' && (
              <div className="space-y-8">
                <ConfigEditor />
                <SnapshotManager />
              </div>
            )}
            {activeTab === 'diff' && <DiffViewer />}
            {activeTab === 'rationale' && <RationalePanel />}
            {activeTab === 'hardware' && (
              serverPayload && !showManualHardware ? (
                <DetectedHardwareCard onEditManually={() => setShowManualHardware(true)} />
              ) : (
                <HardwareWizard
                  onCompleteHardwareBaseline={() => {
                    completeSetupStep('hardware');
                    setSetupStepReady('preset');
                    addToast('Hardware baseline applied.', 'success');
                  }}
                />
              )
            )}
            {activeTab === 'conflicts' && <PluginConflictDetector />}
          </div>

          {/* ── Step footer (setup steps only) ───────────────────────── */}
          {isSetupTab && (
            <div className="flex items-center justify-between gap-3 rounded-xl border border-[var(--border)] bg-[var(--bg-card)] px-4 py-3">
              <button
                onClick={handlePreviousStep}
                disabled={activeStepIndex === 0}
                className="rounded-lg border border-[var(--border)] px-3 py-2 text-xs text-[var(--text-secondary)] transition-colors hover:bg-[var(--bg-elevated)] disabled:opacity-30 sm:text-sm"
              >
                ← Previous
              </button>

              <span className="text-xs text-[var(--text-muted)]">
                Step {activeStepIndex + 1} of {SETUP_STEPS.length}
              </span>

              <button
                onClick={handleStepPrimaryAction}
                disabled={isChangesCTADisabled}
                className="rounded-lg bg-[var(--accent)] px-4 py-2 text-xs font-medium text-white transition-colors hover:bg-[var(--accent-hover)] disabled:opacity-50 sm:text-sm"
              >
                {stepDisplay.primaryLabel} →
              </button>
            </div>
          )}
        </div>
      </div>

      <ToastStack />
      <ImportExportModal />
    </>
  );
}