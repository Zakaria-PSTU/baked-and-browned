// CI guard: the committed API client must match docs/api-contract.yaml.
// Regenerates it, then fails if anything under the output directory changed or appeared.
import { execFileSync } from 'node:child_process';

const output = 'src/app/core/api/generated';

execFileSync('npx', ['ng-openapi-gen'], { stdio: 'inherit', shell: process.platform === 'win32' });

const changes = execFileSync(
  'git',
  ['status', '--porcelain', '--untracked-files=all', '--', output],
  {
    encoding: 'utf8',
  },
).trim();

if (changes) {
  console.error(`The generated API client is out of date with docs/api-contract.yaml:\n${changes}`);
  console.error('Run "npm run api:generate" and commit the result.');
  process.exit(1);
}
console.log('Generated API client matches the contract.');
