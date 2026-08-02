const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { runTests } = require('@vscode/test-electron');

const repoRoot = path.resolve(__dirname, '..', '..', '..');
const sourceExtensionPath = path.resolve(repoRoot, 'studio', 'vscode');
const extensionTestsPath = path.resolve(__dirname, 'suite', 'index.js');
const fixturesRoot = path.resolve(__dirname, 'fixtures');

function transcriptFiles() {
  return fs.readdirSync(fixturesRoot)
    .filter(name => name.endsWith('.jsonl'))
    .sort()
    .map(name => path.join(fixturesRoot, name));
}

function parseMeta(filePath) {
  const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const parsed = JSON.parse(trimmed);
    return parsed.meta ?? {};
  }
  return {};
}

function packageWorld(world, extensionCopy) {
  const result = spawnSync(
    'sbt',
    ['-batch', `runMain stratum.cli.Stratum lsp package --world ${world} --out ${extensionCopy}`],
    { cwd: repoRoot, stdio: 'inherit' }
  );
  if (result.status !== 0) {
    throw new Error(`failed to package ${world} into ${extensionCopy}`);
  }
}

async function runTranscript(filePath) {
  const meta = parseMeta(filePath);
  const world = meta.world ?? 'applications/sds';
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'stratum-studio-test-'));
  const extensionCopy = path.join(scratch, 'extension');
  fs.cpSync(sourceExtensionPath, extensionCopy, { recursive: true });
  try {
    packageWorld(world, extensionCopy);
    await runTests({
      extensionDevelopmentPath: extensionCopy,
      extensionTestsPath,
      extensionTestsEnv: {
        STRATUM_STUDIO_TRANSCRIPT: filePath,
        STRATUM_STUDIO_WORLD: world
      },
      launchArgs: [
        repoRoot,
        '--disable-workspace-trust',
        '--skip-welcome',
        '--skip-release-notes',
        '--new-window'
      ]
    });
  } finally {
    fs.rmSync(scratch, { recursive: true, force: true });
  }
}

async function main() {
  for (const filePath of transcriptFiles()) {
    process.stdout.write(`running ${path.basename(filePath)}\n`);
    await runTranscript(filePath);
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});