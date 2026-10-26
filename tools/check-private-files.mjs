import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';

// Scan the exact staged snapshot, never print credential values.
const git = process.env.GIT_EXECUTABLE || 'git';
const args = ['-c', `safe.directory=${process.cwd().replaceAll('\\', '/')}`];
const paths = execFileSync(git, [...args, 'diff', '--cached', '--name-only', '--diff-filter=ACM', '-z']).toString().split('\0').filter(Boolean);
let secrets = [];
try {
  const signing = await readFile('signing.properties', 'utf8');
  secrets.push(...signing.split(/\r?\n/).filter(line => /^(storePassword|keyPassword)=/.test(line)).map(line => line.slice(line.indexOf('=')+1)).filter(value => value.length >= 12));
} catch (error) { if (error.code !== 'ENOENT') throw new Error('Cannot read local signing inputs; contents hidden.'); }
try {
  const headers = JSON.parse(await readFile('.local/login/headers.json', 'utf8'));
  secrets.push(...(headers.Cookie || '').split(';').map(part => part.slice(part.indexOf('=') + 1)).filter(value => value.length >= 6));
} catch (error) { if (error.code !== 'ENOENT') throw new Error('Cannot parse local credential inputs; contents hidden.'); }
const failures = [];
for (const path of paths) {
  if (/^(\.local\/|research\/local\/)|(^|\/)signing\.properties$|\.(apk|keystore|jks|p12|pfx)$/.test(path)) { failures.push(path); continue; }
  const data = execFileSync(git, [...args, 'show', `:${path}`], { maxBuffer: 32 * 1024 * 1024 }).toString();
  if (secrets.some(secret => data.includes(secret))) failures.push(path);
}
if (failures.length) { console.error('Private content found in staged files:', failures); process.exitCode = 1; }
else console.log(`Checked ${paths.length} staged files: no local account credentials or private paths found.`);
