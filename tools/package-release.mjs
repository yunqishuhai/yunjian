import { readFile, copyFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(root, 'app/build/outputs/apk/release');
const output = resolve(root, 'artifacts/release');
const metadata = JSON.parse(await readFile(resolve(source, 'output-metadata.json'), 'utf8'));
if (metadata.elements.length !== 1 || metadata.applicationId !== 'io.github.miyouchat') throw new Error('Expected one universal Yunjian APK');
const element = metadata.elements[0];
if (!/^\d+\.\d+\.\d+$/.test(element.versionName) || !Number.isSafeInteger(element.versionCode) || element.versionCode <= 0) throw new Error('A stable version and positive versionCode are required');
const expected = process.env.RELEASE_TAG;
if (expected && expected !== `v${element.versionName}`) throw new Error('Tag does not match APK version');
if (element.outputFile.includes('/') || element.outputFile.includes('\\') || element.outputFile.includes('unsigned')) throw new Error('Expected signed release output');
const bytes = await readFile(resolve(source, element.outputFile));
const name = `yunjian-${element.versionName}.apk`;
const sha256 = createHash('sha256').update(bytes).digest('hex');
await mkdir(output, { recursive: true });
await copyFile(resolve(source, element.outputFile), resolve(output, name));
await writeFile(resolve(output, 'update.json'), JSON.stringify({ schemaVersion: 1, versionCode: element.versionCode,
  versionName: element.versionName, minSdk: 26, apkName: name, bytes: bytes.length, sha256 }, null, 2) + '\n');
await writeFile(resolve(output, 'SHA256SUMS'), `${sha256}  ${name}\n`);
console.log(`Prepared ${name}, update.json and SHA256SUMS in artifacts/release`);
