'use client';

import { useEffect } from 'react';
import { useLessLagStore } from '@/store/lesslag-store';
import type { Toast } from '@/store/lesslag-store';

const TYPE_STYLES: Record<Toast['type'], string> = {
  success: 'border-[var(--success)]/30 bg-[var(--success)]/10 text-[var(--success)]',
  error:   'border-[var(--danger)]/30  bg-[var(--danger)]/10  text-[var(--danger)]',
  warning: 'border-[var(--warning)]/30 bg-[var(--warning)]/10 text-[var(--warning)]',
  info:    'border-[var(--info)]/30    bg-[var(--info)]/10    text-[var(--info)]',
};

const TYPE_ICONS: Record<Toast['type'], string> = {
  success: '✓',
  error:   '✕',
  warning: '⚠',
  info:    'ℹ',
};

function ToastItem({ id, message, type }: Toast) {
  const { dismissToast } = useLessLagStore();

  useEffect(() => {
    const timer = setTimeout(() => dismissToast(id), 4500);
    return () => clearTimeout(timer);
  }, [id, dismissToast]);

  return (
    <div
      className={`flex items-start gap-2.5 rounded-lg border px-3.5 py-2.5 shadow-lg text-sm animate-in slide-in-from-right-4 fade-in duration-200 ${TYPE_STYLES[type]}`}
    >
      <span className="shrink-0 mt-px font-bold">{TYPE_ICONS[type]}</span>
      <span className="flex-1 leading-snug">{message}</span>
      <button
        onClick={() => dismissToast(id)}
        className="shrink-0 ml-1 opacity-50 hover:opacity-100 transition-opacity text-base leading-none"
        aria-label="Dismiss"
      >
        ×
      </button>
    </div>
  );
}

export function ToastStack() {
  const { toasts } = useLessLagStore();
  if (toasts.length === 0) return null;

  return (
    <div className="fixed bottom-5 right-4 z-50 flex flex-col gap-2 w-72 sm:w-80">
      {toasts.map((t) => (
        <ToastItem key={t.id} {...t} />
      ))}
    </div>
  );
}
