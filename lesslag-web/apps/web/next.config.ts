import type { NextConfig } from 'next';
import path from 'path';

const nextConfig: NextConfig = {
  transpilePackages: ['@lesslag/shared-rules'],
  webpack(config) {
    // The shared-rules package uses .js extensions in its TS imports (ESM convention).
    // When Next.js resolves from source (via tsconfig paths), webpack needs to
    // try .ts before .js so it can find the actual TypeScript files.
    config.resolve.extensionAlias = {
      '.js': ['.ts', '.js'],
      '.mjs': ['.mts', '.mjs'],
    };
    return config;
  },
};

export default nextConfig;
