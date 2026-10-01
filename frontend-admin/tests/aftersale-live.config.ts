import { defineConfig } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// Deliberately opt-in. Credentials remain in the backend owner's ignored runtime.
const runtimePath = process.env.LIVE_AFS_RUNTIME;
const runtime = runtimePath ? JSON.parse(readFileSync(resolve(runtimePath), 'utf8')) as { backendOrigin: string } : undefined;
const baseURL = 'http://127.0.0.1:4174';
const output = resolve('..', '.cache', 'aftersale-joint-qa', 'browser');

export default defineConfig({
  testDir: '.', testMatch: 'aftersale-live.spec.ts', fullyParallel: false, workers: 1,
  timeout: 120_000, expect: { timeout: 20_000 }, outputDir: output,
  reporter: [['list'], ['json', { outputFile: resolve(output, 'results.json') }]],
  // Network traces can contain real session bearers. Keep them out of artifacts.
  use: { baseURL, viewport: { width: 1440, height: 900 }, trace: 'off', screenshot: 'off', video: 'off' },
  webServer: runtime ? {
    command: 'npm run preview -- --port 4174 --strictPort', url: baseURL,
    reuseExistingServer: false,
    env: { ADMIN_API_PROXY_TARGET: runtime.backendOrigin, ADMIN_API_PROXY_ORIGIN: baseURL },
  } : undefined,
});
