const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const vscode = require('vscode');

function parseTranscript(filePath) {
  const compiledPath = process.env.STRATUM_STUDIO_TRANSCRIPT_JSON;
  if (compiledPath) {
    const compiled = JSON.parse(fs.readFileSync(compiledPath, 'utf8'));
    return (compiled.steps ?? []).map(normalizeStep);
  }
  return fs.readFileSync(filePath, 'utf8')
    .split(/\r?\n/)
    .map((line, index) => ({ line, index: index + 1 }))
    .filter(({ line }) => {
      const trimmed = line.trim();
      return trimmed && !trimmed.startsWith('#');
    })
    .map(({ line, index }) => {
      try {
        return JSON.parse(line);
      } catch (error) {
        throw new Error(`${filePath}:${index}: ${error instanceof Error ? error.message : String(error)}`);
      }
    })
    .filter(step => !step.meta);
}

function normalizeText(value) {
  if (Array.isArray(value)) {
    return `${value.join('\n')}\n`;
  }
  return value;
}

function normalizeStep(step) {
  if (step.writeFile?.text !== undefined) {
    return {
      ...step,
      writeFile: {
        ...step.writeFile,
        text: normalizeText(step.writeFile.text)
      }
    };
  }
  if (step.replaceFile?.text !== undefined) {
    return {
      ...step,
      replaceFile: {
        ...step.replaceFile,
        text: normalizeText(step.replaceFile.text)
      }
    };
  }
  return step;
}

function fullRange(document) {
  const start = new vscode.Position(0, 0);
  const end = document.lineAt(document.lineCount - 1).range.end;
  return new vscode.Range(start, end);
}

function absolutePath(workspaceRoot, relativePath) {
  return path.resolve(workspaceRoot, relativePath);
}

function fileUri(workspaceRoot, relativePath) {
  return vscode.Uri.file(absolutePath(workspaceRoot, relativePath));
}

async function snapshot() {
  await new Promise(resolve => setTimeout(resolve, 50));
  return vscode.commands.executeCommand('stratum.test.snapshot');
}

async function waitForDiagnostics(uri, expectedMessages) {
  const wanted = [...expectedMessages].sort();
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const messages = vscode.languages.getDiagnostics(uri).map(diagnostic => diagnostic.message).sort();
    if (JSON.stringify(messages) === JSON.stringify(wanted)) {
      return messages;
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  return vscode.languages.getDiagnostics(uri).map(diagnostic => diagnostic.message).sort();
}

async function openFile(workspaceRoot, relativePath) {
  const document = await vscode.workspace.openTextDocument(fileUri(workspaceRoot, relativePath));
  await vscode.window.showTextDocument(document, { preview: false });
}

async function replaceFile(workspaceRoot, relativePath, text) {
  const uri = fileUri(workspaceRoot, relativePath);
  const document = await vscode.workspace.openTextDocument(uri);
  const editor = await vscode.window.showTextDocument(document, { preview: false });
  await editor.edit(edit => edit.replace(fullRange(document), text));
}

async function runStep(step, workspaceRoot) {
  if (step.writeFile) {
    const target = absolutePath(workspaceRoot, step.writeFile.path);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, step.writeFile.text);
    return;
  }
  if (step.openFile) {
    await openFile(workspaceRoot, step.openFile.path);
    return;
  }
  if (step.replaceFile) {
    await replaceFile(workspaceRoot, step.replaceFile.path, step.replaceFile.text);
    return;
  }
  if (step.select) {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
      throw new Error('no active editor to select in');
    }
    const start = new vscode.Position(step.select.start.line, step.select.start.character);
    const end = new vscode.Position(step.select.end.line, step.select.end.character);
    editor.selection = new vscode.Selection(start, end);
    return;
  }
  if (step.command) {
    await vscode.commands.executeCommand(step.command.id, ...(step.command.args ?? []));
    return;
  }
  if (step.clearTrace) {
    await vscode.commands.executeCommand('stratum.test.clearTrace');
    return;
  }
  if (step.assertViews) {
    const state = await snapshot();
    assert.deepStrictEqual(state.views, step.assertViews);
    return;
  }
  if (step.assertDocuments) {
    const state = await snapshot();
    assert.deepStrictEqual(state.documents, step.assertDocuments);
    return;
  }
  if (step.assertTraceContains) {
    const state = await snapshot();
    const methods = state.trace.filter(entry => entry.kind === 'request').map(entry => entry.method);
    for (const method of step.assertTraceContains) {
      assert(methods.includes(method), `expected trace to include ${method}; saw ${methods.join(', ')}`);
    }
    return;
  }
  if (step.assertOutputContains) {
    const state = await snapshot();
    for (const fragment of step.assertOutputContains) {
      assert(
        state.output.some(line => line.includes(fragment)),
        `expected output to include ${fragment}; saw ${state.output.join(' | ')}`
      );
    }
    return;
  }
  if (step.assertDiagnostics) {
    const uri = fileUri(workspaceRoot, step.assertDiagnostics.path);
    const messages = await waitForDiagnostics(uri, step.assertDiagnostics.messages);
    assert.deepStrictEqual(messages, [...step.assertDiagnostics.messages].sort());
    return;
  }
  throw new Error(`unknown transcript step ${JSON.stringify(step)}`);
}

exports.run = async function run() {
  const transcript = process.env.STRATUM_STUDIO_TRANSCRIPT;
  if (!transcript) {
    throw new Error('STRATUM_STUDIO_TRANSCRIPT is not set');
  }
  const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!workspaceRoot) {
    throw new Error('workspace folder is required for studio replay tests');
  }
  const scratch = path.join(workspaceRoot, '.studio-test');
  fs.rmSync(scratch, { recursive: true, force: true });
  fs.mkdirSync(scratch, { recursive: true });

  const extension = vscode.extensions.all.find(candidate => candidate.packageJSON?.name === 'stratum-studio' || candidate.packageJSON?.name === 'stratum-smalltalk');
  if (!extension) {
    throw new Error('stratum studio extension is not installed in the test host');
  }
  await extension.activate();
  await vscode.commands.executeCommand('stratum.test.clearTrace');

  try {
    for (const step of parseTranscript(transcript)) {
      await runStep(step, workspaceRoot);
    }
  } finally {
    fs.rmSync(scratch, { recursive: true, force: true });
  }
};