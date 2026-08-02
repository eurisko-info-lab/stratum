const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { runTests } = require('@vscode/test-electron');

const repoRoot = path.resolve(__dirname, '..', '..', '..');
const sourceExtensionPath = path.resolve(repoRoot, 'studio', 'vscode');
const extensionTestsPath = path.resolve(__dirname, 'suite', 'index.cjs');
const fixturesRoot = path.resolve(__dirname, 'fixtures');

function transcriptFiles() {
  return fs.readdirSync(fixturesRoot)
    .filter(name => name.endsWith('.studio'))
    .sort()
    .map(name => path.join(fixturesRoot, name));
}

function compileTranscript(filePath, outPath) {
  const result = spawnSync(
    'sbt',
    ['-batch', `repoTool/runMain stratum.repo.StudioTranscriptTool --script ${path.relative(repoRoot, filePath)} --out ${path.relative(repoRoot, outPath)}`],
    { cwd: repoRoot, stdio: 'inherit' }
  );
  if (result.status !== 0) {
    throw new Error(`failed to compile studio transcript ${filePath}`);
  }
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

function worldExists(world) {
  return fs.existsSync(path.resolve(repoRoot, world, 'service.canon'));
}

async function runTranscript(filePath) {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'stratum-studio-test-'));
  const compiledTranscript = path.join(scratch, 'transcript.json');
  compileTranscript(filePath, compiledTranscript);
  const meta = JSON.parse(fs.readFileSync(compiledTranscript, 'utf8')).meta ?? {};
  const world = meta.world ?? 'applications/sds';
  if (!worldExists(world)) {
    process.stdout.write(`skipping ${path.basename(filePath)} (missing ${world})\n`);
    fs.rmSync(scratch, { recursive: true, force: true });
    return;
  }
  const extensionCopy = path.join(scratch, 'extension');
  fs.cpSync(sourceExtensionPath, extensionCopy, { recursive: true });
  const previousCwd = process.cwd();
  try {
    packageWorld(world, extensionCopy);
    process.chdir(scratch);
    await runTests({
      extensionDevelopmentPath: extensionCopy,
      extensionTestsPath,
      extensionTestsEnv: {
        STRATUM_STUDIO_TRANSCRIPT: filePath,
        STRATUM_STUDIO_TRANSCRIPT_JSON: compiledTranscript,
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
    process.chdir(previousCwd);
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