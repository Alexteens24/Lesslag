'use client';

import { useEffect, useState } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';
import { PresetSelector } from '@/components/PresetSelector';
import { ConfigEditor } from '@/components/ConfigEditor';
import { DiffViewer } from '@/components/DiffViewer';
import { RationalePanel } from '@/components/RationalePanel';
import { ImportExportModal } from '@/components/ImportExportModal';
import { HardwareWizard } from '@/components/HardwareWizard';
import { PluginConflictDetector } from '@/components/PluginConflictDetector';
import { SnapshotManager } from '@/components/SnapshotManager';
import { useShareableState, generateShareUrl } from '@/hooks/useShareableState';

const TABS = [
  { id: 'presets' as const, label: 'Presets' },
  { id: 'editor' as const, label: 'Editor' },
  { id: 'diff' as const, label: 'Changes' },
  { id: 'rationale' as const, label: 'Analysis' },
  { id: 'hardware' as const, label: 'Hardware' },
  { id: 'conflicts' as const, label: 'Plugins' },
] as const;

const SETUP_STEPS = [
  {
    id: 'hardware',
    title: 'Hardware Baseline',
    description: 'Select hosting profile, CPU, RAM, and server platform details.',
    tab: 'hardware' as const,
  },
  {
    id: 'preset',
    title: 'Profile & Preset',
    description: 'Choose game profile, tier, and aggressiveness for recommendations.',
    tab: 'presets' as const,
  },
  {
    id: 'analysis',
    title: 'Analyze Configuration',
    description: 'Generate recommendation analysis from your selected inputs.',
    tab: 'rationale' as const,
  },
  {
    id: 'changes',
    title: 'Review Proposed Changes',
    description: 'Inspect and validate generated configuration differences.',
    tab: 'diff' as const,
  },
  {
    id: 'export',
    title: 'Finalize & Export',
    description: 'Apply/export configs and optionally save a snapshot.',
    tab: 'editor' as const,
  },
] as const;

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
  } = useLessLagStore();

  const [isCreatingCloudLink, setIsCreatingCloudLink] = useState(false);
  const [cloudMessage, setCloudMessage] = useState<string | null>(null);
  const [lastCloudSessionUrl, setLastCloudSessionUrl] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);

  useShareableState();

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const url = window.localStorage.getItem(LAST_CLOUD_SESSION_KEY);
    if (url) {
      setLastCloudSessionUrl(url);
    }
  }, []);

  const activeStepIndex = Math.max(0, SETUP_STEPS.findIndex((step) => step.tab === activeTab));
  const currentStep = SETUP_STEPS[activeStepIndex];

  const stepCompletion = {
    hardware: setupProgress.hardware === 'done',
    preset: setupProgress.preset === 'done',
    analysis: setupProgress.analysis === 'done',
    changes: setupProgress.changes === 'done',
    export: setupProgress.export === 'done',
  } as const;

  const completedCount = Object.values(stepCompletion).filter(Boolean).length;
  const progressPercent = Math.round((completedCount / SETUP_STEPS.length) * 100);

  const goToStep = (stepIndex: number) => {
    const bounded = Math.max(0, Math.min(stepIndex, SETUP_STEPS.length - 1));
    setActiveTab(SETUP_STEPS[bounded].tab);
  };

  const handleStepPrimaryAction = () => {
    switch (currentStep.id) {
      case 'hardware': {
        completeSetupStep('hardware');
        setSetupStepReady('preset');
        setCloudMessage('Hardware baseline saved. Continue with profile and preset.');
        goToStep(1);
        return;
      }
      case 'preset': {
        generatePresetAction();
        runEvaluation();
        setSetupStepReady('analysis');
        setCloudMessage('Recommendations generated. Review analysis next.');
        goToStep(2);
        return;
      }
      case 'analysis': {
        if (!evaluation) {
          runEvaluation();
        }
        completeSetupStep('analysis');
        setSetupStepReady('changes');
        setCloudMessage('Analysis reviewed. Continue to proposed changes.');
        goToStep(3);
        return;
      }
      case 'changes': {
        if (diffs.length === 0) {
          setCloudMessage('No proposed changes available yet. Generate recommendations first.');
          return;
        }
        if (selectedProposals.size === 0) {
          setCloudMessage('Select at least one proposed change before applying.');
          return;
        }
        const selectedCount = selectedProposals.size;
        applySelectedProposals();
        completeSetupStep('changes');
        setSetupStepReady('export');
        setCloudMessage(`Applied ${selectedCount} selected change${selectedCount > 1 ? 's' : ''}.`);
        goToStep(4);
        return;
      }
      case 'export': {
        completeSetupStep('export');
        setShowImportModal(true);
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
    setCloudMessage('Local share link copied to clipboard.');
  };

  const handleCreateCloudLink = async () => {
    if (isCreatingCloudLink) return;

    setIsCreatingCloudLink(true);
    setCloudMessage('Creating cloud session link…');

    try {
      const response = await fetch(`${API_URL}/api/sessions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          profile,
          tier,
          aggressiveness,
          playerCount,
          plugins,
          hardware,
          platform,
          configs,
          serverName: 'LessLag Configurator Session',
        }),
      });

      const body = (await response.json()) as {
        url?: string;
        token?: string;
        message?: string;
      };

      if (!response.ok) {
        throw new Error(body.message ?? `Request failed (${response.status})`);
      }

      const url = body.url ?? (body.token ? `${window.location.origin}/session/${body.token}` : null);
      if (!url) {
        throw new Error('API did not return a session URL.');
      }

      await navigator.clipboard.writeText(url);

      if (typeof window !== 'undefined') {
        window.localStorage.setItem(LAST_CLOUD_SESSION_KEY, url);
      }
      setLastCloudSessionUrl(url);
      setCloudMessage('Cloud link copied to clipboard.');
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to create cloud session link.';
      setCloudMessage(message);
    } finally {
      setIsCreatingCloudLink(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Hero section */}
      <section className="text-center py-4 sm:py-6">
        <h1 className="text-2xl font-bold text-[var(--text-primary)] sm:text-3xl">
          Server Optimizer
        </h1>
        <p className="mt-2 text-sm text-[var(--text-muted)] max-w-xl mx-auto sm:text-base">
          Choose your server profile, review AI-powered recommendations, and export optimized configs.
        </p>
      </section>

      {/* Action bar */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-2">
          {evaluation && (
            <span className="rounded-full bg-[var(--success)]/10 text-[var(--success)] px-3 py-1 text-xs font-medium">
              {evaluation.results.length} findings
            </span>
          )}
          {diffs.length > 0 && (
            <span className="rounded-full bg-[var(--accent)]/10 text-[var(--accent)] px-3 py-1 text-xs font-medium">
              {diffs.length} changes
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleShare}
            className="rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors sm:px-3 sm:py-2 sm:text-sm"
            title="Copy local share link"
          >
            <span className="hidden sm:inline">Share Local</span>
            <span className="sm:hidden">Share</span>
          </button>
          <button
            onClick={handleCreateCloudLink}
            disabled={isCreatingCloudLink}
            className="rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors disabled:opacity-60 sm:px-3 sm:py-2 sm:text-sm"
            title="Create cloud session link"
          >
            <span className="hidden sm:inline">Share Cloud</span>
            <span className="sm:hidden">Cloud</span>
          </button>
          <button
            onClick={() => setActiveTab('editor')}
            className="relative rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors sm:px-3 sm:py-2 sm:text-sm"
            title="Snapshots"
          >
            <span className="hidden sm:inline">Snapshots</span>
            <span className="sm:hidden">Save</span>
            {snapshots.length > 0 && (
              <span className="absolute -top-1.5 -right-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-[var(--accent)] text-[10px] text-white">
                {snapshots.length}
              </span>
            )}
          </button>
          <button
            onClick={() => setShowImportModal(true)}
            className="rounded-lg border border-[var(--border)] px-2.5 py-1.5 text-xs text-[var(--text-secondary)] hover:bg-[var(--bg-elevated)] transition-colors sm:px-3 sm:py-2 sm:text-sm"
          >
            <span className="hidden sm:inline">Import / Export</span>
            <span className="sm:hidden">I/O</span>
          </button>
        </div>
      </div>

      {(cloudMessage || lastCloudSessionUrl) && (
        <div className="rounded-lg border border-[var(--border)] bg-[var(--bg-card)] px-3 py-2 text-xs text-[var(--text-secondary)] sm:text-sm">
          {cloudMessage && <p>{cloudMessage}</p>}
          {lastCloudSessionUrl && (
            <p className="mt-1">
              Last cloud session:{' '}
              <a
                href={lastCloudSessionUrl}
                target="_blank"
                rel="noreferrer"
                className="text-[var(--accent)] hover:underline"
              >
                Open link
              </a>
            </p>
          )}
        </div>
      )}

      {/* Guided setup */}
      <section className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-4 sm:p-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-base font-semibold text-[var(--text-primary)] sm:text-lg">Guided Setup</h2>
            <p className="text-xs text-[var(--text-muted)] sm:text-sm">
              Complete setup in order for the most reliable recommendations.
            </p>
          </div>
          <div className="text-xs text-[var(--text-muted)] sm:text-sm">
            Step {activeStepIndex + 1} of {SETUP_STEPS.length}
          </div>
        </div>

        <div className="mt-3">
          <div className="mb-1 flex items-center justify-between text-xs text-[var(--text-muted)] sm:text-sm">
            <span>{completedCount}/{SETUP_STEPS.length} steps completed</span>
            <span>{progressPercent}%</span>
          </div>
          <div className="h-2 rounded-full bg-[var(--bg-primary)]">
            <div
              className="h-2 rounded-full bg-[var(--accent)] transition-all"
              style={{ width: `${progressPercent}%` }}
            />
          </div>
        </div>

        <div className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-5">
          {SETUP_STEPS.map((step, index) => (
            <button
              key={step.id}
              onClick={() => goToStep(index)}
              className={`rounded-lg border px-3 py-2 text-left transition-colors ${
                index === activeStepIndex
                  ? 'border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--text-primary)]'
                  : setupProgress[step.id] === 'done'
                    ? 'border-[var(--success)]/40 bg-[var(--success)]/10 text-[var(--text-primary)]'
                    : setupProgress[step.id] === 'ready'
                      ? 'border-[var(--accent)]/30 bg-[var(--accent)]/5 text-[var(--text-secondary)]'
                      : 'border-[var(--border)] bg-[var(--bg-primary)] text-[var(--text-secondary)] hover:border-[var(--border-hover)]'
              }`}
            >
              <div className="flex items-center justify-between gap-2 text-xs font-medium sm:text-sm">
                <span>{index + 1}. {step.title}</span>
                {setupProgress[step.id] === 'done' ? (
                  <span className="rounded-full bg-[var(--success)]/15 px-2 py-0.5 text-[10px] text-[var(--success)]">Done</span>
                ) : setupProgress[step.id] === 'ready' ? (
                  <span className="rounded-full bg-[var(--accent)]/15 px-2 py-0.5 text-[10px] text-[var(--accent)]">Ready</span>
                ) : (
                  <span className="rounded-full bg-[var(--bg-elevated)] px-2 py-0.5 text-[10px] text-[var(--text-muted)]">Pending</span>
                )}
              </div>
            </button>
          ))}
        </div>

        <div className="mt-3 rounded-lg border border-[var(--border)] bg-[var(--bg-primary)] px-3 py-2 text-xs text-[var(--text-muted)] sm:text-sm">
          {SETUP_STEPS[activeStepIndex].description}
        </div>

        <div className="mt-4 flex items-center justify-between gap-2">
          <button
            onClick={handlePreviousStep}
            disabled={activeStepIndex === 0}
            className="rounded-lg border border-[var(--border)] px-3 py-2 text-xs text-[var(--text-secondary)] transition-colors hover:bg-[var(--bg-elevated)] disabled:opacity-40 sm:text-sm"
          >
            Previous Step
          </button>
          <div className="flex items-center gap-2">
            <button
              onClick={handleStepPrimaryAction}
              disabled={currentStep.id === 'changes' && (diffs.length === 0 || selectedProposals.size === 0)}
              className="rounded-lg bg-[var(--accent)] px-3 py-2 text-xs font-medium text-white transition-colors hover:bg-[var(--accent-hover)] sm:text-sm"
            >
              {currentStep.id === 'hardware' && 'Save Hardware'}
              {currentStep.id === 'preset' && 'Generate Recommendations'}
              {currentStep.id === 'analysis' && 'Review Findings'}
              {currentStep.id === 'changes' && 'Apply Selected Changes'}
              {currentStep.id === 'export' && 'Export / Share'}
            </button>
          </div>
        </div>
      </section>

      {/* Advanced navigation */}
      <section className="rounded-xl border border-[var(--border)] bg-[var(--bg-card)] p-3 sm:p-4">
        <div className="flex items-center justify-between gap-2">
          <div>
            <h3 className="text-sm font-semibold text-[var(--text-primary)] sm:text-base">Advanced Mode</h3>
            <p className="text-xs text-[var(--text-muted)] sm:text-sm">Direct access to all tools and tabs.</p>
          </div>
          <button
            onClick={() => setShowAdvanced((prev) => !prev)}
            className="rounded-lg border border-[var(--border)] px-3 py-2 text-xs text-[var(--text-secondary)] transition-colors hover:bg-[var(--bg-elevated)] sm:text-sm"
          >
            {showAdvanced ? 'Hide Advanced' : 'Show Advanced'}
          </button>
        </div>

        {showAdvanced && (
          <nav className="mt-3 grid grid-cols-3 gap-1 rounded-lg bg-[var(--bg-primary)] border border-[var(--border)] p-1 sm:flex">
            {TABS.map((tab) => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`rounded-md px-2 py-2 text-xs font-medium transition-all sm:flex-1 sm:px-3 sm:py-2.5 sm:text-sm ${
                  activeTab === tab.id
                    ? 'bg-[var(--accent)] text-white shadow-sm'
                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-elevated)]'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </nav>
        )}
      </section>

      {/* Tab content */}
      <main className="min-h-[60vh]">
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
          <HardwareWizard
            onCompleteHardwareBaseline={() => {
              completeSetupStep('hardware');
              setSetupStepReady('preset');
              setCloudMessage('Hardware baseline applied. Continue with profile and preset.');
            }}
          />
        )}
        {activeTab === 'conflicts' && <PluginConflictDetector />}
      </main>

      <ImportExportModal />
    </div>
  );
}
