import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {atomicJson, validateConfig, registerManager, unregisterManager, managerDisposition, runManager, runManagers, renderUnits} from './main.mjs';

const executable = fileURLToPath(new URL('./main.mjs', import.meta.url));
const target = {paseoId: 'manager-one'};
const workspace = {id: 'workspace-one', workspaceDirectory: '/project'};
const snapshot = {id: target.paseoId, workspaceId: workspace.id, cwd: '/project', status: 'idle', runtimeInfo: {sessionId: 'native-one'}};

function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manager-watchdog-test-'));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  const configFile = path.join(dir, 'config.json');
  const config = {intervalSeconds: 600, note: 'preserved', managers: [{...target, runtimeFile: '/inaccessible/legacy/runtime.json', extra: 7}]};
  fs.writeFileSync(configFile, JSON.stringify(config));
  return {dir, configFile, config, stateDir: path.join(dir, 'state')};
}

function clientFixture() {
  const calls = {fetch: [], sends: [], close: 0, timeline: 0};
  let reads = 0;
  const client = {
    statuses: ['idle'], timeline: [],
    async fetchAgent({agentId}) {
      calls.fetch.push(agentId);
      return {agent: {...snapshot, id: agentId, status: this.statuses[Math.min(reads++, this.statuses.length - 1)]}};
    },
    async fetchWorkspaces() { return {entries: [workspace]}; },
    async fetchAgentTimeline() { calls.timeline++; return {entries: this.timeline}; },
    async sendAgentMessage(...args) { calls.sends.push(args); },
    async close() { calls.close++; },
  };
  return {client, calls};
}

test('config accepts empty registration and ignores legacy runtime paths while preserving extra fields', () => {
  const config = {intervalSeconds: 600, managers: [{...target, runtimeFile: 'not-a-local-path', custom: true}], custom: 42};
  assert.equal(validateConfig(config), config);
  assert.deepEqual(validateConfig({intervalSeconds: 600, managers: []}).managers, []);
  for (const invalid of [
    {intervalSeconds: 0, managers: []}, {intervalSeconds: 600},
    {intervalSeconds: 600, managers: [target, target]},
    {intervalSeconds: 600, managers: [{paseoId: '../escape'}]},
    {intervalSeconds: 600, managers: [{...target, paseoHost: ''}]},
  ]) assert.throws(() => validateConfig(invalid));
});

test('register/unregister are idempotent, preserve all projects/interval/extras and allow version end', t => {
  const {configFile, config} = fixture(t);
  const initial = fs.readFileSync(configFile, 'utf8');
  assert.equal(registerManager(configFile, target.paseoId).changed, false);
  assert.equal(fs.readFileSync(configFile, 'utf8'), initial);
  const remote = 'ssh://code2hack@u4090';
  assert.equal(registerManager(configFile, 'manager-two', remote).changed, true);
  assert.equal(registerManager(configFile, 'manager-two', remote).changed, false);
  assert.equal(registerManager(configFile, target.paseoId, remote).changed, true);
  assert.equal(registerManager(configFile, target.paseoId).changed, false);
  const updated = JSON.parse(fs.readFileSync(configFile));
  assert.equal(updated.intervalSeconds, 600); assert.equal(updated.note, 'preserved');
  assert.deepEqual(updated.managers[0], {...config.managers[0], paseoHost: remote});
  assert.equal(fs.statSync(configFile).mode & 0o777, 0o600);
  assert.equal(unregisterManager(configFile, 'absent').changed, false);
  unregisterManager(configFile, target.paseoId);
  assert.deepEqual(unregisterManager(configFile, 'manager-two').config.managers, []);
});

test('config conflicts and failed atomic publication retain original bytes and remove temporary files', t => {
  const {dir, configFile} = fixture(t), original = fs.readFileSync(configFile, 'utf8');
  fs.writeFileSync(configFile + '.lock', 'another writer');
  assert.throws(() => registerManager(configFile, 'manager-two'), /EEXIST/);
  assert.equal(fs.readFileSync(configFile, 'utf8'), original);
  fs.unlinkSync(configFile + '.lock');
  const rename = fs.renameSync;
  try {
    fs.renameSync = () => { throw Error('fixture rename failure'); };
    assert.throws(() => registerManager(configFile, 'manager-two'), /fixture rename failure/);
  } finally { fs.renameSync = rename; }
  assert.equal(fs.readFileSync(configFile, 'utf8'), original);
  assert.deepEqual(fs.readdirSync(dir), ['config.json']);
});

test('idle Manager wakes on every tick without reading any project file', async t => {
  const {stateDir} = fixture(t), {client, calls} = clientFixture();
  client.sendAgentMessage = async (id, body, options) => {
    const latest = JSON.parse(fs.readFileSync(path.join(stateDir, 'wakes', id, 'latest.json')));
    assert.equal(latest.state, 'PREPARED'); assert.equal(latest.body, body);
    assert.equal(options.messageId, latest.messageId); assert.equal(options.activeTurnBehavior, 'steer');
    calls.sends.push([id, body, options]);
  };
  const first = await runManager({...target, runtimeFile: '/does-not-exist', status: 'waiting'}, {client, stateDir});
  const second = await runManager(target, {client, stateDir});
  assert.equal(first.mode, 'wake-api-accepted'); assert.equal(second.mode, 'wake-api-accepted');
  assert.equal(calls.sends.length, 2); assert.notEqual(first.messageId, second.messageId);
  assert.deepEqual(calls.fetch, Array(4).fill(target.paseoId));
  assert.match(calls.sends[0][1], /own project runtime.json/);
  assert.match(calls.sends[0][1], /Workers and Reviewers/);
  assert.match(calls.sends[0][1], /Briefly report progress to the Owner/);
  assert.match(calls.sends[0][1], /guardRequests.*timed-out denied proposals flagged askOnNextWatchdog/);
  assert.match(calls.sends[0][1], /new correlated request and preserving the prior denial/);
  assert.match(calls.sends[0][1], /timeout is a dismissal/);
  assert.equal(JSON.parse(fs.readFileSync(first.eventFile)).recipientReceipt, false);
});

test('busy, pending permission, archived and unavailable Managers are skipped', async t => {
  const {stateDir} = fixture(t), {client, calls} = clientFixture();
  client.statuses = ['running'];
  assert.equal((await runManager(target, {client, stateDir})).mode, 'manager-running-defer');
  assert.equal(calls.sends.length, 0); assert.equal(fs.existsSync(stateDir), false);
  assert.equal(managerDisposition(target, {agent: null}, [workspace]), 'manager-missing');
  for (const [actual, spaces, expected] of [
    [{...snapshot, pendingPermissions: [{}]}, [workspace], 'manager-permission-defer'],
    [{...snapshot, attentionReason: 'permission'}, [workspace], 'manager-permission-defer'],
    [{...snapshot, archivedAt: 'today'}, [workspace], 'manager-archived'],
    [snapshot, [{...workspace, archivingAt: 'today'}], 'manager-archived'],
    [{...snapshot, status: 'stopped'}, [workspace], 'manager-unavailable'],
    [{...snapshot, attentionReason: 'error'}, [workspace], 'manager-unavailable'],
  ]) assert.equal(managerDisposition(target, {agent: actual}, spaces), expected);
});

test('fresh Manager/workspace and registration rechecks prevent a stale steer', async t => {
  const {stateDir} = fixture(t);
  for (const mode of ['running', 'workspace', 'registration']) {
    const {client, calls} = clientFixture();
    if (mode === 'running') client.statuses = ['idle', 'running'];
    let wsReads = 0;
    if (mode === 'workspace') client.fetchWorkspaces = async () => ({entries: ++wsReads === 1 ? [workspace] : []});
    const loadConfig = mode === 'registration' ? () => ({managers: []}) : undefined;
    const result = await runManager(target, {client, stateDir, loadConfig});
    assert.equal(result.mode, {running: 'manager-running-defer', workspace: 'manager-workspace-mismatch', registration: 'manager-registration-changed'}[mode]);
    assert.equal(calls.sends.length, 0);
  }
  assert.equal(fs.existsSync(stateDir), false);
});

test('read-only check observes availability without writes or messages', async t => {
  const {dir, stateDir} = fixture(t), {client, calls} = clientFixture();
  const before = fs.readdirSync(dir);
  assert.equal((await runManager(target, {client, stateDir, checkOnly: true})).mode, 'would-wake');
  assert.deepEqual(fs.readdirSync(dir), before); assert.equal(calls.sends.length, 0);
});

test('unknown send keeps exact journal and reconciles only its ID and unchanged body', async t => {
  const {stateDir} = fixture(t), {client, calls} = clientFixture();
  client.sendAgentMessage = async (...args) => { calls.sends.push(args); throw Error('lost reply'); };
  const result = await runManager(target, {client, stateDir});
  assert.equal(result.mode, 'wake-uncertain');
  const record = JSON.parse(fs.readFileSync(result.eventFile));
  assert.equal(record.state, 'UNKNOWN');
  assert.equal((await runManager(target, {client, stateDir})).mode, 'previous-wake-uncertain');
  client.timeline = [{item: {type: 'user_message', clientMessageId: record.messageId, text: 'changed body'}}];
  assert.equal((await runManager(target, {client, stateDir})).mode, 'previous-wake-uncertain');
  client.timeline[0].item.text = record.body;
  const before = fs.readFileSync(result.eventFile, 'utf8');
  assert.equal((await runManager(target, {client, stateDir, checkOnly: true})).mode, 'previous-wake-uncertain');
  assert.equal(fs.readFileSync(result.eventFile, 'utf8'), before);
  assert.equal((await runManager(target, {client, stateDir})).mode, 'previous-wake-reconciled');
  assert.equal(calls.sends.length, 1);
  assert.equal(JSON.parse(fs.readFileSync(result.eventFile)).reconciledFromTimeline, true);
  client.sendAgentMessage = async (...args) => { calls.sends.push(args); };
  assert.equal((await runManager(target, {client, stateDir})).mode, 'wake-api-accepted');
  assert.equal(calls.sends.length, 2);
});

test('corrupt journal and unobserved legacy PREPARED send remain pending', async t => {
  const {stateDir} = fixture(t), {client, calls} = clientFixture();
  const latest = path.join(stateDir, 'wakes', target.paseoId, 'latest.json');
  atomicJson(latest, {state: 'PREPARED', messageId: 'old', body: 'old body'});
  assert.equal((await runManager(target, {client, stateDir})).mode, 'previous-wake-uncertain');
  fs.writeFileSync(latest, '{');
  assert.equal((await runManager(target, {client, stateDir})).mode, 'wake-record-error');
  assert.equal(calls.sends.length, 0);
});

test('each registered Manager uses its own daemon; failures preserve independent monitoring', async t => {
  const {stateDir} = fixture(t), hosts = [], clients = [];
  const config = {intervalSeconds: 600, managers: [target, {paseoId: 'manager-two', paseoHost: 'ssh://u4090'}, {paseoId: 'manager-three'}, {paseoId: 'manager-four', paseoHost: 'ssh://unavailable'}]};
  const results = await runManagers(config, {stateDir, checkOnly: true, connect: async options => {
    hosts.push(options);
    if (options.host === 'ssh://unavailable') throw Error('not connected');
    const fixture = clientFixture(); clients.push(fixture); return fixture.client;
  }});
  assert.deepEqual(hosts, [{timeout: 10000}, {timeout: 10000, host: 'ssh://u4090'}, {timeout: 10000, host: 'ssh://unavailable'}]);
  assert.deepEqual(results.map(result => result.mode), ['would-wake', 'would-wake', 'would-wake', 'manager-check-error']);
  assert.ok(clients.every(({calls}) => calls.close === 1));
  assert.deepEqual(await runManagers({intervalSeconds: 600, managers: []}, {connect: () => { throw Error('must stay unused'); }}), []);
});

test('CLI register/unregister and check use portable client injection without import-time connection', t => {
  const {dir, configFile} = fixture(t);
  const sdk = path.join(dir, 'sdk.mjs');
  fs.writeFileSync(sdk, `export async function connectToDaemon() { return {
    fetchAgent: async ({agentId}) => ({agent: {id:agentId,workspaceId:'w',cwd:'/project',status:'idle'}}),
    fetchWorkspaces: async () => ({entries:[{id:'w',workspaceDirectory:'/project'}]}),
    sendAgentMessage: async () => {}, close: async () => {}
  }; }`);
  const cli = (...args) => JSON.parse(execFileSync(process.execPath, [executable, '--config', configFile, ...args], {encoding: 'utf8', env: {...process.env, HOME: dir}}));
  assert.equal(cli('--register', 'manager-two', '--paseo-host', 'ssh://u4090').changed, true);
  assert.equal(cli('--check', '--client', sdk).results.length, 2);
  assert.equal(fs.existsSync(path.join(dir, '.local/state')), false);
  assert.ok(cli('--run', '--locked', '--client', sdk).results.every(result => result.mode === 'wake-api-accepted'));
  assert.ok(cli('--run', '--client', sdk).results.every(result => result.mode === 'wake-api-accepted'));
  assert.equal(cli('--write-units', '--client', sdk).intervalSeconds, 600);
  assert.match(fs.readFileSync(path.join(dir, '.config/systemd/user/manager-watchdog.service'), 'utf8'), /--run/);
  assert.equal(cli('--unregister', 'manager-two').changed, true);
  assert.equal(cli('--unregister', target.paseoId).changed, true);
  assert.deepEqual(cli('--check').results, []);
  const imported = spawnSync(process.execPath, ['--input-type=module', '-e', `await import(${JSON.stringify(new URL('./main.mjs', import.meta.url).href)})`], {encoding: 'utf8', env: {...process.env, PASEO_CLIENT: '/missing/sdk'}});
  assert.equal(imported.status, 0); assert.equal(imported.stdout, '');
});

test('units retain the shared interval, command bounds and supported locked run interface', () => {
  const units = renderUnits({intervalSeconds: 600, managers: [target]}, '/config/config.json', '/sdk/client.js');
  assert.match(units['manager-watchdog.timer'], /OnUnitActiveSec=600s/);
  assert.match(units['manager-watchdog.service'], /--config \/config\/config.json --client \/sdk\/client.js --run/);
  assert.match(units['manager-watchdog.service'], /TimeoutStartSec=95/);
});


test('unit rendering supports the scoped installed Paseo SDK path', () => {
  const sdk = '/home/fixture/.npm-global/lib/node_modules/@getpaseo/cli/dist/utils/client.js';
  const units = renderUnits({intervalSeconds: 600, managers: [target, {paseoId: 'manager-two'}]}, '/home/fixture/watchdog/config.json', sdk);
  assert.ok(units['manager-watchdog.service'].includes('--client ' + sdk + ' --run'));
  assert.ok(units['manager-watchdog.service'].includes('TimeoutStartSec=170'));
});
