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
      <body className="min-h-screen antialiased">
        <header className="border-b border-[var(--border)] bg-[var(--bg-secondary)]">
          <div className="mx-auto flex max-w-7xl items-center justify-between px-3 py-2.5 sm:px-6 sm:py-3">
            <div className="flex items-center gap-2 sm:gap-3">
              <span className="text-lg font-bold text-[var(--accent)] sm:text-xl">⚡ LessLag</span>
              <span className="hidden text-sm text-[var(--text-muted)] sm:inline">
                Minecraft Server Optimizer
              </span>
            </div>
            <nav className="flex items-center gap-4 text-sm text-[var(--text-secondary)]">
              <a href="https://github.com/Alexteens24/LessLag" target="_blank" rel="noopener"
                className="hover:text-[var(--text-primary)] transition-colors">
                GitHub
              </a>
            </nav>
          </div>
        </header>
        <main className="mx-auto max-w-7xl px-3 py-4 sm:px-6 sm:py-6">
          {children}
        </main>
        <Analytics />
        <SpeedInsights />
      </body>
    </html>
  );
}
