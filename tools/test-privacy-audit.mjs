import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync, unlinkSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(fileURLToPath(new URL('..', import.meta.url)));
const directory = resolve(workspace, '.local/privacy-audit-tests');
mkdirSync(directory, { recursive: true });
const git = process.env.GIT_EXECUTABLE || 'git';
const script = name => resolve(workspace, 'tools', name);
function fixture() {
  // Synthetic values only. Fixtures remain in the ignored local test directory.
  const root = mkdtempSync(join(directory, 'case-'));
  const args = ['-c', `safe.directory=${root.replaceAll('\\','/')}`, '-C', root];
  const run = (...command) => execFileSync(git, [...args, ...command], { stdio: 'pipe' });
  run('init','--initial-branch=main');
  run('config','user.name','Privacy test'); run('config','user.email','fixture@example.invalid');
  const password = randomBytes(24).toString('hex'); const token = randomBytes(24).toString('hex');
  mkdirSync(resolve(root,'.local/login'), { recursive: true });
  writeFileSync(resolve(root,'signing.properties'), `storePassword=${password}\nkeyPassword=${password}\n`);
  writeFileSync(resolve(root,'.local/login/headers.json'), JSON.stringify({ Cookie: `stoken=${token}; stuid=123456789` }));
  const stage = (name, contents) => { writeFileSync(resolve(root,name),contents); run('add','--',name); };
  const audit = (name = 'audit-public-source.mjs', extra = []) => spawnSync(process.execPath,[script(name),...extra],{
    cwd: root, env: { ...process.env,GIT_EXECUTABLE: git },encoding: 'utf8',maxBuffer: 1024*1024,
  });
  return { root,run,stage,audit,password,token };
}
test('signing passwords remain protected when a local cookie file also exists', () => {
  const f = fixture(); f.stage('payload.txt',f.password);
  const result = f.audit('check-private-files.mjs');
  assert.equal(result.status,1); assert.ok(!result.stdout.includes(f.password)); assert.ok(!result.stderr.includes(f.password));
});
test('known numeric account identifiers are rejected without printing them', () => {
  const f = fixture(); f.stage('README.md','account: 123456789');
  const result = f.audit();
  assert.equal(result.status,1); assert.ok(result.stdout.includes('known-account-value')); assert.ok(!result.stdout.includes('123456789'));
});
test('a deleted secret is still detected in earlier commits', () => {
  const f = fixture(); f.stage('payload.txt',f.token); f.run('commit','-m','Synthetic fixture');
  unlinkSync(resolve(f.root,'payload.txt')); f.run('add','-u'); f.stage('README.md','Public source'); f.run('commit','-m','Remove synthetic fixture');
  assert.equal(f.audit().status,0);
  const result = f.audit('audit-public-source.mjs',['--history']);
  assert.equal(result.status,1); assert.ok(result.stdout.includes('known-account-value')); assert.ok(!result.stdout.includes(f.token));
});
test('personal machine paths cannot be committed as documentation', () => {
  const f = fixture(); f.stage('README.md',['E:', 'Project', 'private'].join(String.fromCharCode(92)));
  const result = f.audit(); assert.equal(result.status,1); assert.ok(result.stdout.includes('personal-windows-path'));
});
test('Kotlin qualified receivers and explicit URL-security fixtures are not email addresses', () => {
  const f = fixture();
  f.stage('Sample.kt',['this','@','ChatRepository.notifications.post()\n"https://user@github.com/example/path"'].join(''));
  assert.equal(f.audit().status,0);
});
test('a public noreply-style author and a clean source tree pass full-history scanning', () => {
  const f = fixture(); f.stage('README.md','Public source'); f.run('commit','-m','Public initial commit');
  assert.equal(f.audit('audit-public-source.mjs',['--history']).status,0);
});
test('generic token formats are detected even without any locally known credential', () => {
  const f = fixture(); const key = ['g','hp_'].join('') + randomBytes(24).toString('hex'); f.stage('payload.txt',key);
  const result = f.audit(); assert.equal(result.status,1); assert.ok(result.stdout.includes('github-token')); assert.ok(!result.stdout.includes(key));
});
test('malformed private JSON cannot echo credential contents into error logs', () => {
  const f = fixture(); f.stage('README.md','Public source');
  writeFileSync(resolve(f.root,'.local/login/headers.json'),`{"Cookie": "${f.token}`);
  for (const checker of ['audit-public-source.mjs','check-private-files.mjs']) {
    const result = f.audit(checker); assert.notEqual(result.status,0);
    assert.ok(!result.stdout.includes(f.token)); assert.ok(!result.stderr.includes(f.token));
  }
});
test('GitHub web commit service email is public metadata, not a personal mailbox', () => {
  const f = fixture(); f.run('config','user.email','noreply@github.com');
  f.stage('README.md','Public source'); f.run('commit','-m','Synthetic web commit');
  assert.equal(f.audit('audit-public-source.mjs',['--history']).status,0);
});
