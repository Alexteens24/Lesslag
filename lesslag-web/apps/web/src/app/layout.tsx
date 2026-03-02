import type { Metadata } from 'next';
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
          <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-3">
            <div className="flex items-center gap-3">
              <span className="text-xl font-bold text-[var(--accent)]">⚡ LessLag</span>
              <span className="hidden text-sm text-[var(--text-muted)] sm:inline">
                Minecraft Server Optimizer
              </span>
            </div>
            <nav className="flex items-center gap-4 text-sm text-[var(--text-secondary)]">
              <a href="https://github.com/alexisbinh/lesslag" target="_blank" rel="noopener"
                className="hover:text-[var(--text-primary)] transition-colors">
                GitHub
              </a>
            </nav>
          </div>
        </header>
        <main className="mx-auto max-w-7xl px-6 py-6">
          {children}
        </main>
      </body>
    </html>
  );
}
