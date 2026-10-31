// Kept as the short command: generates every scenario through from-route.mjs.
// Usage: node tools/scenario/build.mjs [id ...]
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const generator = fileURLToPath(new URL('./from-route.mjs', import.meta.url));
execFileSync(process.execPath, [generator, ...process.argv.slice(2)], { stdio: 'inherit' });
