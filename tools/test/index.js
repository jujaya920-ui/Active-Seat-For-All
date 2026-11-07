// Node 24 treats `node --test tools/test/` as a module path, not a directory to scan, and
// resolves it to this file. Loading the test files here makes that command work.
import('./scenario.test.mjs');
import('./console-proxy.test.mjs');
