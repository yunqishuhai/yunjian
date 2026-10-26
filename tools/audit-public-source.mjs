import { execFileSync } from 'node:child_process';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';

// Only locations and rule names are reported, never matching values or source lines.
const args = process.argv.slice(2);
const option = name => { const index = args.indexOf(name); return index < 0 ? null : args[index + 1]; };
const root = resolve(option('--root') || '.');
const privateRoot = resolve(option('--private-root') || root);
const git = process.env.GIT_EXECUTABLE || 'git';
const gitArgs = ['-c', `safe.directory=${root.replaceAll('\\', '/')}`, '-C', root];
const run = extra => execFileSync(git, [...gitArgs, ...extra], { maxBuffer: 128 * 1024 * 1024 });
const known = new Map();
function add(value, label) {
  if (typeof value === 'string' && value.trim().length >= 6) known.set(value.trim(), label);
}
async function optional(path) {
  try { return await readFile(resolve(privateRoot, path), 'utf8'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; return null; }
}
function parsePrivateJson(text) {
  try { return JSON.parse(text || '{}'); }
  catch { throw new Error('A local privacy input is not valid JSON; its contents are intentionally hidden.'); }
}
const headers = parsePrivateJson(await optional('.local/login/headers.json'));
for (const [name, value] of Object.entries(headers)) {
  if (name.toLowerCase() === 'cookie') for (const part of value.split(';')) {
    const separator = part.indexOf('='); if (separator >= 0) add(part.slice(separator + 1), 'known-account-value');
  }
  else if (/authorization|token|secret|device/i.test(name)) add(value, 'known-private-header');
}
for (const path of ['.local/login/device.json', '.local/login/pending.json', '.local/login/send-plan.json']) {
  const json = parsePrivateJson(await optional(path));
  for (const [name, value] of Object.entries(json)) if (/token|secret|password|device|chat.?id|account.?id|^aid$|^mid$|^uid$/i.test(name)) add(value, 'known-private-identifier');
}
for (const line of (await optional('signing.properties') || '').split(/\r?\n/)) {
  if (/^(storePassword|keyPassword)=/.test(line)) add(line.slice(line.indexOf('=') + 1), 'known-signing-password');
}
const forbiddenPath = /^(?:\.local\/|research\/|artifacts\/)|(?:^|\/)(?:signing\.properties|local\.properties|\.env(?:\..*)?)$|\.(?:apk|aab|jks|keystore|p12|pfx|sqlite|sqlite3|db|har|log)$/i;
const patterns = [
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----/g],
  ['github-token', /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})\b/g],
  ['openai-key', /\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{24,}\b/g],
  ['aws-access-key', /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g],
  ['personal-windows-path', /\b[A-Z]:[\\/]+(?:Users|Project|Projects|Downloads|workspace|repos|environment)[\\/]+[^\s"'<>]+/gi],
  ['personal-home-path', /\/(?:Users|home)\/[A-Za-z0-9_.-]+\//g],
];
const findings = [];
const addFinding = (rule, file, revision, line = null) => findings.push({ rule, file, revision, line });
function inspect(file, data, revision) {
  if (forbiddenPath.test(file)) addFinding('private-or-investigation-file', file, revision);
  for (const [value, label] of known) if (data.includes(Buffer.from(value))) addFinding(label, file, revision);
  if (data.includes(0)) return;
  const text = data.toString('utf8');
  for (const [rule, pattern] of patterns) {
    pattern.lastIndex = 0;
    for (const match of text.matchAll(pattern)) addFinding(rule, file, revision, text.slice(0, match.index).split('\n').length);
  }
  const email = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
  for (const match of text.matchAll(email)) {
    if (file.endsWith('.kt') && /^this@[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(match[0])) continue;
    if (match[0] === 'user@github.com') continue; // Synthetic URL-userinfo rejection fixture.
    if (match[0].toLowerCase() === 'noreply@github.com') continue; // GitHub web commit service identity.
    const domain = match[0].split('@')[1].toLowerCase();
    if (!/^(?:example\.(?:com|org|net)|.*\.(?:test|invalid))$/.test(domain)) addFinding('personal-email', file, revision, text.slice(0, match.index).split('\n').length);
  }
}
let scanned = 0;
if (args.includes('--history')) {
  const entries = run(['rev-list', '--objects', '--all']).toString().trim().split('\n').filter(Boolean);
  const objects = new Map(entries.map(line => { const separator = line.indexOf(' '); return [separator < 0 ? line : line.slice(0, separator), separator < 0 ? '' : line.slice(separator + 1)]; }));
  const kinds = execFileSync(git, [...gitArgs, 'cat-file', '--batch-check=%(objectname) %(objecttype)'], { input: [...objects.keys()].join('\n'), maxBuffer: 32 * 1024 * 1024 }).toString().trim().split('\n');
  for (const line of kinds) {
    const [oid, type] = line.split(' '); if (type !== 'blob') continue;
    inspect(objects.get(oid) || '(unknown path)', run(['cat-file', 'blob', oid]), oid); scanned++;
  }
  const identities = run(['log', '--all', '--format=%ae%n%ce']).toString().trim().split('\n').filter(Boolean);
  for (const identity of new Set(identities)) {
    if (!/^(?:.*@(?:users\.noreply\.github\.com|example\.(?:com|org|net)|[A-Za-z0-9.-]+\.(?:invalid|test))|noreply@github\.com|codex@local)$/i.test(identity)) addFinding('personal-commit-email', '(commit metadata)', 'history');
  }
} else {
  const files = run(['ls-files', '-z']).toString().split('\0').filter(Boolean);
  for (const file of files) { inspect(file, run(['show', `:${file}`]), 'index'); scanned++; }
}
const report = { scope: args.includes('--history') ? 'all-history' : 'tracked-index', scanned, findings };
const reportPath = option('--report');
if (reportPath) { await mkdir(dirname(resolve(reportPath)), { recursive: true }); await writeFile(resolve(reportPath), JSON.stringify(report, null, 2) + '\n'); }
const counts = {};
for (const finding of findings) counts[finding.rule] = (counts[finding.rule] || 0) + 1;
console.log(JSON.stringify({ scope: report.scope, scanned, findings: findings.length, rules: counts }));
if (findings.length) process.exitCode = 1;
