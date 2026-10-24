/** Bounded harness processes and private, non-overwriting command attempts. No service calls. */
import { spawn } from 'node:child_process';
import { mkdirSync, lstatSync, realpathSync, readFileSync, copyFileSync, existsSync, constants } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { writePrivate, dataFile } from './runtime.mjs';

export const BOUNDS = Object.freeze({ browser: 540000, browserStartup: 30000, browserShutdown: 10000,
  http: 15000, scenario: 45000, backendShutdown: 30000, teardown: 60000, command: 540000 });
export async function bounded(operation, milliseconds, label) {
  if (!Number.isFinite(milliseconds) || milliseconds <= 0) throw Error(label + ': deadline exceeded');
  let timer;
  try { return await Promise.race([Promise.resolve().then(operation), new Promise((_, reject) => {
    timer = setTimeout(() => reject(Error(label + ': deadline exceeded')), milliseconds);
  })]); } finally { clearTimeout(timer); }
}
export function privateDirectory(path) {
  if (!/^\/(private\/)?tmp\/(pathome-oe-|pathome-routing-c07-correction-20261024-01)[A-Za-z0-9_/-]*$/.test(path)
      || path.split('/').includes('..')) throw Error('Invalid private directory');
  const parts = path.split('/').filter(Boolean); let cursor = '';
  for (const part of parts) {
    cursor += '/' + part;
    try { if (cursor !== '/tmp' && lstatSync(cursor).isSymbolicLink()) throw Error('Symlinked private directory'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; mkdirSync(cursor, { mode: 0o700 }); }
  }
  const stat = lstatSync(path);
  if (!stat.isDirectory() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw Error('Owner-only directory required');
  return path;
}
export function commandAttempt(root, command, database) {
  const directory = privateDirectory(join(root, 'evidence', 'commands', Date.now() + '-' + randomUUID()));
  const record = { command, database, runId: null, startedAt: new Date().toISOString(), endedAt: null,
    status: 'RUNNING', checkpoint: 'START', checkpoints: [], directory };
  try { record.runId = readFileSync(join(root, 'run-id'), 'utf8').trim(); } catch { /* pre-create */ }
  const save = () => writePrivate(join(directory, 'command.json'), record);
  save(); return { directory, record, save };
}
export function runProcess(executable, args, { env, cwd, milliseconds, onSpawn = () => {}, interrupted = false } = {}) {
  if (!Number.isInteger(milliseconds) || milliseconds <= 0 || milliseconds > BOUNDS.command) throw Error('Invalid process deadline');
  return new Promise(resolve => {
    const child = spawn(executable, args, { env, cwd, detached: true, stdio: 'inherit' });
    let timedOut = false, signal = null, settled = false, force, fallback;
    const kill = name => { try { if (child.pid) process.kill(-child.pid, name); } catch (error) { if (error.code !== 'ESRCH') signal = 'KILL_REFUSED'; } };
    const terminate = name => {
      if (settled || force) return;
      signal = name; kill('SIGTERM');
      force = setTimeout(() => kill('SIGKILL'), 3000);
      fallback = setTimeout(() => finish({ code: 124, signal, timedOut }), 4000);
    };
    const deadline = setTimeout(() => { timedOut = true; terminate('DEADLINE'); }, milliseconds);
    const int = () => terminate('SIGINT'), term = () => terminate('SIGTERM');
    const finish = result => {
      if (settled) return; settled = true; clearTimeout(deadline); clearTimeout(force); clearTimeout(fallback);
      process.removeListener('SIGINT', int); process.removeListener('SIGTERM', term); resolve(result);
    };
    process.once('SIGINT', int); process.once('SIGTERM', term);
    child.once('error', () => finish({ code: 90, signal: 'SPAWN_FAILED', timedOut: false }));
    child.once('close', (code, childSignal) => finish({ code: timedOut ? 124 : signal ? 130 : code ?? 1, signal: signal ?? childSignal, timedOut }));
    try { onSpawn(child.pid); } catch { terminate('JOURNAL_FAILED'); }
    if (interrupted) terminate('SIGTERM');
  });
}
async function supervise(script, command) {
  const root = process.env.PATHOME_OE_TEST_RUN_DIR;
  const allowed = ['preflight','create','recreate','backend','stop-backend','recover','recover-probe','provision','verify','attest','teardown','acceptance','browser','scheduling-tests','b09-fixtures','b09-browser'];
  if (!allowed.includes(command) || realpathSync(script) !== script) throw Error('Unapproved command');
  privateDirectory(root);
  const attempt = commandAttempt(root, command, process.env.PATHOME_OE_TEST_DB);
  const preserve = phase => {
    for (const file of ['provisioning.json','verification.txt','target-attestation.txt']) {
      const source=join(root,'evidence',file);
      if(existsSync(source)) {
        const stat=lstatSync(source);
        if(!stat.isFile() || stat.isSymbolicLink() || stat.uid!==process.getuid() || (stat.mode&0o077)) throw Error('Unsafe prior evidence');
        copyFileSync(source,join(attempt.directory,phase+'-'+file),constants.COPYFILE_EXCL);
      }
    }
  };
  preserve('before');
  const milliseconds = command === 'teardown' || command === 'recover-probe' ? BOUNDS.teardown
    : command === 'stop-backend' || command === 'recover' ? BOUNDS.backendShutdown : BOUNDS.command;
  const result = await runProcess('/bin/bash', [script, command, ...process.argv.slice(5)], { milliseconds,
    env: { ...process.env, OE_COMMAND_SUPERVISED: 'true', OE_COMMAND_ATTEMPT_DIR: attempt.directory },
    onSpawn: pid => { attempt.record.pid = pid; attempt.record.checkpoint = 'CHILD_STARTED'; attempt.save(); } });
  Object.assign(attempt.record,JSON.parse(dataFile(join(attempt.directory,'command.json'))));
  attempt.record.endedAt = new Date().toISOString(); attempt.record.checkpoint = 'CHILD_ENDED';
  try { attempt.record.resultRunId=readFileSync(join(root,'run-id'),'utf8').trim(); } catch { /* absent partial setup */ }
  attempt.record.status = result.timedOut ? 'TIMED_OUT' : result.signal ? 'INTERRUPTED' : result.code === 0 ? 'PASSED' : 'FAILED';
  attempt.record.exitCode = result.code; attempt.save(); preserve('after');
  if (result.code) console.error('Harness command stopped; private diagnostic record: ' + attempt.directory + '/command.json');
  process.exitCode = result.code;
}
export function validateSupervision(record, pid, command, database, now=Date.now()) {
  const age=now-Date.parse(record.startedAt);
  if(record.pid!==Number(pid) || record.command!==command || record.database!==database || record.status!=='RUNNING'
      || !Number.isFinite(age) || age<0 || age>BOUNDS.command+5000) throw Error('Foreign or stale command supervision');
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === 'supervise') supervise(process.argv[3], process.argv[4]).catch(() => { console.error('REFUSED: command supervision unavailable'); process.exitCode = 90; });
  else if (process.argv[2] === 'verify-supervision' || process.argv[2] === 'checkpoint') {
    try {
      const directory=process.env.OE_COMMAND_ATTEMPT_DIR,root=process.env.PATHOME_OE_TEST_RUN_DIR;
      if(!directory?.startsWith(root+'/evidence/commands/') || directory.includes('..')) throw Error('Invalid supervision path');
      const file=join(directory,'command.json'),record=JSON.parse(dataFile(file));
      if(process.argv[2]==='verify-supervision') {
        if(process.ppid!==Number(process.argv[3])) throw Error('Foreign supervision parent');
        validateSupervision(record,process.argv[3],process.argv[4],process.env.PATHOME_OE_TEST_DB);
      } else {
        const checkpoint=process.argv[3];
        if(!/^[A-Z_]{3,40}$/.test(checkpoint) || record.status!=='RUNNING') throw Error('Invalid checkpoint');
        record.checkpoint=checkpoint;record.checkpoints.push({checkpoint,at:new Date().toISOString()});writePrivate(file,record);
      }
    } catch { console.error('REFUSED: private command supervision could not be proven'); process.exitCode=90; }
  }
  else { console.error('REFUSED: unknown lifecycle operation'); process.exitCode = 90; }
}
