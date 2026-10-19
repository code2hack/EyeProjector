import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';

const DEFAULT_CONFIG = path.join(os.homedir(), '.config/manager-watchdog/config.json');
const DEFAULT_STATE = path.join(os.homedir(), '.local/state/manager-watchdog');

export function absolutePath(value) {
  if (typeof value !== 'string' || !value) throw Error('A path is required');
  const expanded = value.startsWith('~/') ? path.join(os.homedir(), value.slice(2)) : value;
  if (!path.isAbsolute(expanded)) throw Error('Paths must be absolute or start with ~/');
  return path.normalize(expanded);
}

export function validateConfig(config) {
  if (!Number.isInteger(config?.intervalSeconds) || config.intervalSeconds < 1) throw Error('intervalSeconds must be a positive integer');
  if (!Array.isArray(config.managers)) throw Error('managers must be an array');
  const ids = new Set();
  for (const entry of config.managers) {
    if (typeof entry?.paseoId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(entry.paseoId)) throw Error('Invalid Manager paseoId');
    if (ids.has(entry.paseoId)) throw Error('Duplicate Manager paseoId');
    if (entry.paseoHost !== undefined && (typeof entry.paseoHost !== 'string' || !entry.paseoHost.trim() || /[\r\n\0]/.test(entry.paseoHost))) throw Error('Invalid paseoHost');
    ids.add(entry.paseoId);
  }
  return config;
}

export function atomicJson(filename, value) {
  fs.mkdirSync(path.dirname(filename), {recursive: true, mode: 0o700});
  const temp = filename + '.' + crypto.randomUUID() + '.tmp';
  let fd;
  try {
    fd = fs.openSync(temp, 'wx', 0o600);
    fs.writeFileSync(fd, JSON.stringify(value, null, 2) + '\n');
    fs.fsyncSync(fd); fs.closeSync(fd); fd = undefined;
    fs.renameSync(temp, filename);
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
    if (fs.existsSync(temp)) fs.unlinkSync(temp);
  }
}

const readConfig = filename => validateConfig(JSON.parse(fs.readFileSync(filename, 'utf8')));

function editConfig(filename, edit) {
  const lockFile = filename + '.lock';
  const lock = fs.openSync(lockFile, 'wx', 0o600);
  try {
    const config = readConfig(filename), before = JSON.stringify(config);
    edit(config); validateConfig(config);
    const changed = JSON.stringify(config) !== before;
    if (changed) atomicJson(filename, config);
    return {changed, config};
  } finally { fs.closeSync(lock); fs.unlinkSync(lockFile); }
}

export function registerManager(filename, paseoId, paseoHost) {
  validateConfig({intervalSeconds: 1, managers: [{paseoId, ...(paseoHost === undefined ? {} : {paseoHost})}]});
  return editConfig(filename, config => {
    const entry = config.managers.find(manager => manager.paseoId === paseoId);
    if (entry) { if (paseoHost !== undefined) entry.paseoHost = paseoHost; }
    else config.managers.push({paseoId, ...(paseoHost === undefined ? {} : {paseoHost})});
  });
}

export function unregisterManager(filename, paseoId) {
  validateConfig({intervalSeconds: 1, managers: [{paseoId}]});
  return editConfig(filename, config => { config.managers = config.managers.filter(entry => entry.paseoId !== paseoId); });
}

async function bounded(promise, milliseconds = 10000) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(Error('Operation timed out')), milliseconds);
    })]);
  } finally { clearTimeout(timer); }
}

export function managerDisposition(entry, raw, workspaces) {
  const actual = raw && Object.hasOwn(raw, 'agent') ? raw.agent : raw;
  if (!actual) return 'manager-missing';
  if (actual.id !== entry.paseoId) return 'manager-identity-mismatch';
  const workspace = workspaces.find(candidate => candidate.id === actual.workspaceId);
  if (!workspace || workspace.workspaceDirectory !== actual.cwd) return 'manager-workspace-mismatch';
  if (actual.archivedAt || workspace.archivingAt || workspace.archivedAt) return 'manager-archived';
  if (actual.pendingPermissions?.length || actual.attentionReason === 'permission') return 'manager-permission-defer';
  if (actual.status === 'running') return 'manager-running-defer';
  if (actual.status !== 'idle' || actual.attentionReason === 'error') return 'manager-unavailable';
  return 'available';
}

async function observe(client, entry) {
  const [raw, workspaces] = await Promise.all([
    bounded(client.fetchAgent({agentId: entry.paseoId})),
    bounded(client.fetchWorkspaces({page: {limit: 200}})),
  ]);
  return {raw, disposition: managerDisposition(entry, raw, workspaces.entries)};
}

export function heartbeatBody(entry, messageId, at) {
  return 'MANAGER WATCHDOG HEARTBEAT ' + at + '\n' +
    'Paseo ID: ' + entry.paseoId + '\nRequest-ID: ' + messageId + '\n\n' +
    'Inspect your own project runtime.json and current version records. Review ongoing missions and the current activities of Workers and Reviewers. ' +
    'Keep expected running/idle/waiting assignments distinct from observed lifecycle state. Harvest available reports and progress authorized work. ' +
    'Briefly report progress to the Owner and what each Worker is doing now. Resolve unanswered expired guard proposals as denied and dismissed in runtime.json. Read guardRequests and re-ask the Owner about timed-out denied proposals flagged askOnNextWatchdog, using a new correlated request and preserving the prior denial. ' +
    'A guard-proposal timeout is a dismissal; continue authorized work under the existing guards. ' +
    'Preserve existing assignments, pending requests, Owner decisions and history.';
}

export async function runManager(entry, options) {
  const {client, stateDir = DEFAULT_STATE, checkOnly = false, loadConfig} = options;
  const base = {managerId: entry.paseoId, ...(entry.paseoHost ? {paseoHost: entry.paseoHost} : {})};
  let observed;
  try { observed = await observe(client, entry); }
  catch (error) { return {...base, mode: 'manager-observation-error', error: error.message}; }
  if (observed.disposition !== 'available') return {...base, mode: observed.disposition};
  const latestFile = path.join(stateDir, 'wakes', entry.paseoId, 'latest.json');
  let previous;
  try { if (fs.existsSync(latestFile)) previous = JSON.parse(fs.readFileSync(latestFile, 'utf8')); }
  catch { return {...base, mode: 'wake-record-error', latestFile}; }
  if (previous && ['PREPARED', 'UNKNOWN'].includes(previous.state)) {
    if (checkOnly) return {...base, mode: 'previous-wake-uncertain', latestFile};
    try {
      const timeline = await bounded(client.fetchAgentTimeline(entry.paseoId, {direction: 'tail', limit: 100, projection: 'canonical'}));
      const item = timeline.entries?.map(value => value.item).find(value =>
        value.type === 'user_message' && (value.clientMessageId ?? value.messageId) === previous.messageId);
      if (!item || item.text !== previous.body) return {...base, mode: 'previous-wake-uncertain', latestFile};
      previous.state = 'API_ACCEPTED'; previous.updatedAt = new Date().toISOString();
      previous.reconciledFromTimeline = true;
      atomicJson(previous.eventFile, previous); atomicJson(latestFile, previous);
      return {...base, mode: 'previous-wake-reconciled', messageId: previous.messageId};
    } catch { return {...base, mode: 'previous-wake-uncertain', latestFile}; }
  }
  if (checkOnly) return {...base, mode: 'would-wake'};
  try {
    if (loadConfig && !loadConfig().managers.some(current => current.paseoId === entry.paseoId && current.paseoHost === entry.paseoHost)) {
      return {...base, mode: 'manager-registration-changed'};
    }
    observed = await observe(client, entry);
    if (observed.disposition !== 'available') return {...base, mode: observed.disposition};
  } catch { return {...base, mode: 'pre-send-recheck-error'}; }
  const at = new Date().toISOString(), messageId = 'manager-watchdog-' + crypto.randomUUID();
  const eventFile = path.join(stateDir, 'wakes', entry.paseoId, messageId + '.json');
  const actual = observed.raw.agent ?? observed.raw;
  const record = {at, messageId, managerId: entry.paseoId, paseoHost: entry.paseoHost ?? null,
    native: actual.runtimeInfo?.sessionId ?? actual.persistence?.sessionId ?? null,
    eventFile, body: heartbeatBody(entry, messageId, at), state: 'PREPARED', recipientReceipt: false};
  atomicJson(eventFile, record); atomicJson(latestFile, record);
  try {
    await bounded(client.sendAgentMessage(entry.paseoId, record.body, {messageId, activeTurnBehavior: 'steer'}), 15000);
    record.state = 'API_ACCEPTED';
  } catch { record.state = 'UNKNOWN'; }
  record.updatedAt = new Date().toISOString();
  atomicJson(eventFile, record); atomicJson(latestFile, record);
  return {...base, mode: record.state === 'API_ACCEPTED' ? 'wake-api-accepted' : 'wake-uncertain', eventFile, messageId};
}

export async function runManagers(config, options) {
  const clients = new Map(), results = [];
  try {
    for (const entry of validateConfig(config).managers) {
      try {
        const key = entry.paseoHost ?? '';
        if (!clients.has(key)) clients.set(key, await bounded(options.connect({timeout: 10000, ...(key ? {host: key} : {})})));
        results.push(await runManager(entry, {...options, client: clients.get(key)}));
      } catch (error) { results.push({managerId: entry.paseoId, mode: 'manager-check-error', error: error.message}); }
    }
  } finally {
    await Promise.allSettled([...clients.values()].map(client => bounded(client.close(), 5000)));
  }
  return results;
}

export function renderUnits(config, configFile, clientFile) {
  const executable = path.join(os.homedir(), 'bin/manager-watchdog');
  if (![executable, configFile, ...(clientFile ? [clientFile] : [])].every(value => /^[A-Za-z0-9_./@-]+$/.test(value))) throw Error('Unsupported systemd installation path');
  return {
    'manager-watchdog.service': '[Unit]\nDescription=Manager heartbeat through Paseo\n\n[Service]\nType=oneshot\nExecStart=' + executable + ' --config ' + configFile + (clientFile ? ' --client ' + clientFile : '') + ' --run\nTimeoutStartSec=' + (20 + config.managers.length * 75) + '\n',
    'manager-watchdog.timer': '[Unit]\nDescription=Wake registered idle Managers\n\n[Timer]\nOnActiveSec=' + config.intervalSeconds + 's\nOnUnitActiveSec=' + config.intervalSeconds + 's\nAccuracySec=1s\nPersistent=false\nUnit=manager-watchdog.service\n\n[Install]\nWantedBy=timers.target\n',
  };
}

async function main() {
  const args = process.argv.slice(2);
  let configFile = DEFAULT_CONFIG, clientFile = process.env.PASEO_CLIENT, mode = '--run', locked = false, paseoId, paseoHost;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--config') configFile = absolutePath(args[++index]);
    else if (arg === '--client') clientFile = absolutePath(args[++index]);
    else if (['--run', '--check', '--write-units'].includes(arg)) mode = arg;
    else if (['--register', '--unregister'].includes(arg)) { mode = arg; paseoId = args[++index]; }
    else if (arg === '--paseo-host') {
      paseoHost = args[++index];
      if (!paseoHost) throw Error('--paseo-host requires a daemon URI');
    }
    else if (arg === '--locked') locked = true;
    else throw Error('Unknown argument: ' + arg);
  }
  if (paseoHost !== undefined && mode !== '--register') throw Error('--paseo-host requires --register');
  if (mode === '--register' || mode === '--unregister') {
    const result = mode === '--register' ? registerManager(configFile, paseoId, paseoHost) : unregisterManager(configFile, paseoId);
    console.log(JSON.stringify({changed: result.changed, managers: result.config.managers.length, intervalSeconds: result.config.intervalSeconds}));
    return;
  }
  const config = readConfig(configFile);
  if (mode === '--write-units') {
    const folder = path.join(os.homedir(), '.config/systemd/user');
    fs.mkdirSync(folder, {recursive: true});
    for (const [name, text] of Object.entries(renderUnits(config, configFile, clientFile))) fs.writeFileSync(path.join(folder, name), text, {mode: 0o600});
    console.log(JSON.stringify({unitsWritten: ['manager-watchdog.timer', 'manager-watchdog.service'], intervalSeconds: config.intervalSeconds, daemonReloadRequired: true}));
    return;
  }
  if (mode === '--run' && !locked) {
    fs.mkdirSync(DEFAULT_STATE, {recursive: true, mode: 0o700});
    try {
      const output = execFileSync('/usr/bin/flock', ['-n', path.join(DEFAULT_STATE, 'watchdog.lock'), process.execPath, fileURLToPath(import.meta.url), ...args, '--locked'], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], timeout: (20 + config.managers.length * 75) * 1000});
      process.stdout.write(output);
    } catch (error) { process.exitCode = error.status || 1; }
    return;
  }
  let results = [];
  if (config.managers.length) {
    if (!clientFile) throw Error('Specify the installed Paseo SDK with --client or PASEO_CLIENT');
    const {connectToDaemon} = await import(pathToFileURL(absolutePath(clientFile)).href);
    results = await runManagers(config, {connect: connectToDaemon, checkOnly: mode === '--check', loadConfig: () => readConfig(configFile)});
  }
  console.log(JSON.stringify({at: new Date().toISOString(), checkOnly: mode === '--check', results}));
  if (results.some(result => /error|uncertain|mismatch|unavailable|missing|archived/.test(result.mode))) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error('manager-watchdog: ' + error.message); process.exitCode = 1; });
}
