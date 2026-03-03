import type { Metadata } from 'next';
import { Analytics } from '@vercel/analytics/next';
import { SpeedInsights } from '@vercel/speed-insights/next';
import './globals.css';

export const metadata: Metadata = {
  title: 'LessLag — Minecraft Server Optimizer',
  description: 'Interactive performance tuning powered by Paper Chan\'s optimization guide. Configure, analyze, and export your server settings.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body className="flex min-h-screen flex-col antialiased">
        <header className="sticky top-0 z-40 border-b border-[var(--border)] bg-[var(--bg-secondary)]/95 backdrop-blur">
          <div className="mx-auto flex max-w-7xl items-center justify-between px-3 py-2.5 sm:px-6 sm:py-3">
            <div className="flex items-center gap-2 sm:gap-3">
              <span className="text-lg font-bold text-[var(--accent)] sm:text-xl">⚡ LessLag</span>
              <span className="hidden text-xs text-[var(--text-muted)] sm:inline">
                Minecraft Server Optimizer
              </span>
            </div>
            <nav className="flex items-center gap-3 text-sm text-[var(--text-secondary)]">
              <a
                href="https://github.com/Alexteens24/LessLag"
                target="_blank"
                rel="noopener"
                className="hover:text-[var(--text-primary)] transition-colors"
              >
                GitHub
              </a>
              <a
                href="https://hangar.papermc.io/"
                target="_blank"
                rel="noopener"
                className="hidden hover:text-[var(--text-primary)] transition-colors sm:inline"
              >
                Hangar
              </a>
            </nav>
          </div>
        </header>

        <main className="mx-auto w-full max-w-7xl flex-1 px-3 py-4 sm:px-6 sm:py-6">
          {children}
        </main>

        <footer className="border-t border-[var(--border)] bg-[var(--bg-secondary)]">
          <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-2 px-3 py-3 text-xs text-[var(--text-muted)] sm:px-6">
            <span>⚡ LessLag — Minecraft Server Optimizer</span>
            <div className="flex items-center gap-4">
              <a
                href="https://github.com/Alexteens24/LessLag"
                target="_blank"
                rel="noopener"
                className="hover:text-[var(--text-secondary)] transition-colors"
              >
                GitHub
              </a>
              <span>Inspired by Paper Chan&apos;s guide</span>
            </div>
          </div>
        </footer>

        <Analytics />
        <SpeedInsights />
      </body>
    </html>
  );
}
