import { readFile, readdir, stat } from 'node:fs/promises';
import { extname, join, relative, resolve } from 'node:path';

const directory = resolve(process.argv[2] ?? 'dist/acompanhaai-web/browser');
const textExtensions = new Set(['.css', '.html', '.js', '.json', '.map', '.mjs', '.svg', '.txt']);
const rules = [
  ['chave Google/Gemini', /\bAIza[\w-]{35}(?![\w-])/],
  ['chave de API sk-', /\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}\b/],
  ['chave privada', /-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----/],
  ['URL PostgreSQL com senha', /\bpostgres(?:ql)?:\/\/[^\s:@/]+:[^\s@/]+@/i],
  [
    'credencial literal',
    /\b(?:password|db[_-]?password|api[_-]?key|gemini[_-]?api[_-]?key|google[_-]?api[_-]?key|openai[_-]?api[_-]?key|client[_-]?secret)["']?\s*[:=]\s*(?:"[^"\r\n]+"|'[^'\r\n]+'|`[^`\r\n]+`)/i,
  ],
];

const findings = [];
let scripts = 0;

async function scan(folder) {
  for (const entry of await readdir(folder, { withFileTypes: true })) {
    const path = join(folder, entry.name);
    const label = relative(directory, path);

    if (
      entry.isSymbolicLink() ||
      /^\.env(?:\.|$)|^application-local\.properties$/i.test(entry.name)
    ) {
      findings.push(`${label} (arquivo impróprio para o artefato)`);
    } else if (entry.isDirectory()) {
      await scan(path);
    } else if (entry.isFile() && textExtensions.has(extname(entry.name).toLowerCase())) {
      const content = await readFile(path, 'utf8');
      if (/\.(?:m?js)$/i.test(entry.name)) {
        scripts += 1;
      }
      for (const [kind, pattern] of rules) {
        if (pattern.test(content)) {
          findings.push(`${label} (${kind})`);
        }
      }
    }
  }
}

try {
  const index = await stat(join(directory, 'index.html'));
  if (!index.isFile() || index.size === 0) {
    throw new Error('index.html ausente ou vazio');
  }
  await scan(directory);
  if (scripts === 0) {
    throw new Error('nenhum JavaScript gerado');
  }
  if (findings.length > 0) {
    console.error('Artefato rejeitado. Revise estes arquivos antes de publicar:');
    for (const finding of findings) {
      console.error(`- ${finding}`);
    }
    process.exitCode = 1;
  } else {
    console.log(`Bundle verificado (${scripts} arquivos JavaScript).`);
  }
} catch (error) {
  console.error(`Não foi possível verificar o build Angular: ${error.message}`);
  process.exitCode = 1;
}
