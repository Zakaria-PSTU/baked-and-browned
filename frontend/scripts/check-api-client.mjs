// CI guard: the committed API client must match docs/api-contract.yaml.
// Regenerates it, then fails if anything under the output directory changed or appeared.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const output = 'src/app/core/api/generated';

// Run the generator's CLI entry with this Node binary: no shell, no npx, same on every OS.
const require = createRequire(import.meta.url);
const manifestPath = require.resolve('ng-openapi-gen/package.json');
const { bin } = JSON.parse(readFileSync(manifestPath, 'utf8'));
execFileSync(process.execPath, [join(dirname(manifestPath), bin['ng-openapi-gen'])], {
  stdio: 'inherit',
});

const changes = execFileSync(
  'git',
  ['status', '--porcelain', '--untracked-files=all', '--', output],
  { encoding: 'utf8' },
).trim();

if (changes) {
  console.error(`The generated API client is out of date with docs/api-contract.yaml:\n${changes}`);
  console.error('Run "npm run api:generate" and commit the result.');
  process.exit(1);
}
console.log('Generated API client matches the contract.');
