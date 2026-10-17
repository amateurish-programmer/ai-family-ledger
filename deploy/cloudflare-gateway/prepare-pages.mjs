import { mkdir, copyFile, writeFile } from 'node:fs/promises';
const output = new URL('./dist-pages/', import.meta.url);
await mkdir(output, { recursive: true });
await copyFile(new URL('./worker.mjs', import.meta.url), new URL('_worker.js', output));
await writeFile(new URL('_routes.json', output), JSON.stringify({ version: 1, include: ['/*'], exclude: [] }) + '\n');
