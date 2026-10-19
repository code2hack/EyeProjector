import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test} from 'node:test';
import {execFileSync, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {atomicUpdate, verifyBinding, validateRuntime, reconcileRuntime, reconcileConfig} from '../scripts/manager-runtime.mjs';

const root = '/tmp/manager-runtime-fixture';
const binding = {project: {name: 'Fixture', root, paseoId: 'prj_fixture'}, agent: {
  host: 'fixture-host', runtime: 'codex',
  id: 'native-1', paseo: 'paseo-1', model: 'fixture-model', workspace: 'wks_fixture',
  cwd: root, effort: 'high', mode: 'full-access',
}};
const options = {name: 'Manager', assignment: '/tmp/fixture-run/run.json', status: 'running',
  repository: 'example/fixture', settings: {notification: 'written', concurrencyDecision: '/tmp/decision'},
  currentRun: {kind: 'initialization', id: 'run-001', record: '/tmp/fixture-run/run.json'}};
const initial = () => reconcileRuntime(null, binding, options);
const monitor = {paseoId: 'paseo-1', runtimeFile: root + '/runtime.json'};
const other = {paseoId: 'other-manager', runtimeFile: '/tmp/other/runtime.json'};

function writeClientFixture(dir) {
  const client = path.join(dir, 'fake-client.mjs');
  fs.writeFileSync(client, `
    import fs from 'node:fs';
    export const getDaemonHost = () => process.env.PASEO_HOST || 'localhost:6767';
    export function resolveDaemonTarget(host) {
      if (host.startsWith('unix://') || host.startsWith('pipe://') || host.startsWith('/')) {
        return {type:'ipc', socketPath:host.replace(/^(unix|pipe):\\/\\//, ''), url:'ws://localhost/ws'};
      }
      return {type:'tcp', url:host.startsWith('tcp://') ? host.replace(/^tcp:/, 'ws:') : 'ws://' + host + '/ws'};
    }
    export async function connectToDaemon(options) {
      if (options.host !== getDaemonHost() || options.timeout !== 10000) throw Error('Endpoint not pinned');
      fs.writeFileSync('connected', 'explicit selected endpoint');
      const root=process.cwd();
      return {
        fetchAgent: async () => ({agent:{id:'fixture-paseo',provider:'codex',cwd:root,workspaceId:'fixture-ws',model:'fixture-model',effectiveThinkingOptionId:'high',runtimeInfo:{sessionId:'fixture-native',modeId:'full-access'}},project:{projectKey:'fixture-project',projectName:'Fixture'}}),
        fetchWorkspaces: async () => ({entries:[{id:'fixture-ws',workspaceDirectory:root,projectRootPath:root,projectId:'fixture-project'}],pageInfo:{nextCursor:null}}),
        close: async () => {},
      };
    }
  `);
  return client;
}

test('initialization, exact identities, private schema and idempotent reconciliation', () => {
  const value = initial();
  assert.equal(value.schemaVersion, 1);
  assert.equal(value.revision, 1);
  assert.equal(value.project.root, root);
  assert.equal(value.agents[0].id, 'native-1');
  assert.equal(value.agents[0].paseo, 'paseo-1');
  assert.deepEqual(reconcileRuntime(value, binding, options), value);
  value.pendingRequests = [{id: 'request-1', state: 'unknown'}];
  value.agents[0].attempts = [{todo: 'todo-1', count: 3}];
  const next = reconcileRuntime(value, {...binding, agent: {...binding.agent, model: 'new-model'}}, {...options, status: 'waiting', settings: {discard: true}, currentRun: null});
  assert.equal(next.revision, 2);
  assert.equal(next.agents[0].status, 'waiting');
  assert.deepEqual(next.settings, value.settings);
  assert.deepEqual(next.currentRun, value.currentRun);
  assert.deepEqual(next.pendingRequests, value.pendingRequests);
  assert.deepEqual(next.agents[0].attempts, value.agents[0].attempts);
});

test('conflicting Manager/project/identity/status fails without displacement', () => {
  const value = initial();
  assert.throws(() => reconcileRuntime(value, {...binding, agent: {...binding.agent, id: 'native-2'}}, options), /Conflicting Manager/);
  assert.throws(() => reconcileRuntime(value, {...binding, project: {...binding.project, paseoId: 'wrong'}}, options), /another project/);
  assert.throws(() => reconcileRuntime(value, binding, {...options, status: 'archived'}), /expected/);
  assert.throws(() => validateRuntime({...value, revision: 0}), /schema\/revision/);
  const managers = structuredClone(value);
  managers.agents.push({...value.agents[0], name: 'Other Manager', id: 'native-other', paseo: 'paseo-other'});
  assert.throws(() => validateRuntime(managers), /Exactly one Manager/);
  const duplicate = structuredClone(value);
  duplicate.agents.push({...value.agents[0], role: 'Worker', name: 'Worker'});
  assert.throws(() => validateRuntime(duplicate), /Duplicate participant/);
  const invalid = structuredClone(value);
  invalid.agents[0].Lifecycle = 'running';
  assert.throws(() => validateRuntime(invalid), /lifecycle/);
  assert.equal(value.agents[0].id, 'native-1');
});

test('handoff replaces only recorded Manager, retaining run, children and history references', () => {
  const value = initial();
  const worker = {...value.agents[0], role: 'Worker', name: 'Worker', id: 'native-worker', paseo: 'paseo-worker', workspace: 'wks_worker', status: 'waiting', assignment: '/tmp/worker-mission.json'};
  value.agents.push(worker);
  value.pendingRequests = [{id: 'pending-request', result: 'unknown'}];
  const nextBinding = {...binding, agent: {...binding.agent, id: 'native-2', paseo: 'paseo-2', workspace: 'wks_next'}};
  const handoff = {previousManager: value.agents[0], successor: {host: 'fixture-host', runtime: 'codex', id: 'native-2', paseo: 'paseo-2'}, record: '/tmp/handoff.json'};
  assert.throws(() => reconcileRuntime(value, nextBinding, {...options, handoff: {...handoff, previousManager: {...handoff.previousManager, status: 'idle'}}}), /recorded handoff/);
  const result = reconcileRuntime(value, nextBinding, {...options, handoff});
  assert.equal(result.agents.filter(a => a.role === 'Manager').length, 1);
  assert.equal(result.agents.find(a => a.role === 'Manager').id, 'native-2');
  assert.deepEqual(result.agents.find(a => a.role === 'Worker'), worker);
  assert.deepEqual(result.currentRun, value.currentRun);
  assert.deepEqual(result.settings, value.settings);
  assert.deepEqual(result.pendingRequests, value.pendingRequests);
  const successorParticipant = {...worker, name: 'Successor Worker', id: 'native-2', paseo: 'paseo-2', workspace: 'wks_next', scope: 'Old implementation assignment', attemptHistory: '/tmp/successor-history.json'};
  value.agents.push(successorParticipant);
  const promoted = reconcileRuntime(value, nextBinding, {...options, handoff});
  assert.equal(promoted.agents.length, 2);
  const manager = promoted.agents.find(a => a.role === 'Manager');
  assert.equal(manager.attemptHistory, successorParticipant.attemptHistory);
  assert.equal(manager.scope, 'Project-wide coordination');
  assert.deepEqual(promoted.agents.find(a => a.role === 'Worker'), worker);
});

test('multiple projects, config idempotency, conflicts, exact handoff and retirement', () => {
  const config = {intervalSeconds: 413, managers: [other], extension: {retained: true}};
  const next = reconcileConfig(config, monitor);
  assert.deepEqual(next.managers, [other, monitor]);
  assert.equal(next.intervalSeconds, 413);
  assert.deepEqual(next.extension, config.extension);
  assert.deepEqual(reconcileConfig(next, monitor), next);
  const successor = {...monitor, paseoId: 'paseo-2'};
  assert.throws(() => reconcileConfig(next, successor), /Conflicting monitor/);
  assert.throws(() => reconcileConfig(next, {...monitor, runtimeFile: '/tmp/wrong/runtime.json'}, {replaceId: monitor.paseoId}), /Conflicting monitor/);
  const switched = reconcileConfig(next, successor, {replaceId: 'paseo-1'});
  assert.deepEqual(switched.managers, [other, successor]);
  assert.deepEqual(reconcileConfig(switched, successor, {remove: true}), config);
  assert.throws(() => reconcileConfig(switched, monitor, {remove: true}), /target mismatch/);
  assert.throws(() => reconcileConfig(config, other, {remove: true}), /Last monitor/);
  assert.deepEqual(config.managers, [other]);
  const tildeEntry = {paseoId: 'home-manager', runtimeFile: '~/project/runtime.json'};
  const tildeConfig = {...config, managers: [other, tildeEntry]};
  const expanded = {...tildeEntry, runtimeFile: path.join(os.homedir(), 'project/runtime.json')};
  assert.deepEqual(reconcileConfig(tildeConfig, expanded), tildeConfig);
  assert.deepEqual(reconcileConfig(tildeConfig, expanded, {remove: true}), config);
});

test('binding observes native identity/project/profile and rejects child masquerading as parent', () => {
  const agent = {id: 'paseo-1', provider: 'codex', cwd: root, workspaceId: 'wks_fixture', model: 'fixture-model', effectiveThinkingOptionId: 'high', runtimeInfo: {sessionId: 'native-1', modeId: 'full-access'}, pendingPermissions: []};
  const raw = {agent, project: {projectKey: 'prj_fixture', projectName: 'Fixture'}};
  const workspaces = [{id: 'wks_fixture', workspaceDirectory: root, projectRootPath: root, projectId: 'prj_fixture'}];
  const identity = {root, paseoId: 'paseo-1', nativeId: 'native-1', host: 'fixture-host'};
  assert.deepEqual(verifyBinding(raw, workspaces, identity), binding);
  assert.throws(() => verifyBinding(raw, workspaces, {...identity, nativeId: 'child-thread'}), /identity mismatch/);
  assert.throws(() => verifyBinding(raw, [{...workspaces[0], projectId: 'other-project'}], identity), /root mismatch/);
  assert.throws(() => verifyBinding({...raw, agent: {...agent, archivedAt: 'date'}}, workspaces, identity), /archived/);
  assert.throws(() => verifyBinding({...raw, agent: {...agent, pendingPermissions: [{}]}}, workspaces, identity), /gated/);
  assert.throws(() => verifyBinding({...raw, agent: {...agent, model: null}}, workspaces, identity), /profile/);
});

test('atomic private writes, interruption rollback, stale writer refusal, concurrency and symlinks', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manager-runtime-test-'));
  const filename = path.join(dir, 'runtime.json');
  try {
    assert.equal(atomicUpdate(filename, () => initial()).changed, true);
    assert.equal(fs.statSync(filename).mode & 0o777, 0o600);
    const original = fs.readFileSync(filename, 'utf8');
    assert.equal(atomicUpdate(filename, old => old).changed, false);
    assert.throws(() => atomicUpdate(filename, old => ({...old, revision: 2}), () => { throw Error('simulated interruption'); }), /interruption/);
    assert.equal(fs.readFileSync(filename, 'utf8'), original);
    assert.deepEqual(fs.readdirSync(dir), ['runtime.json']);
    assert.throws(() => atomicUpdate(filename, old => ({...old, revision: 2}), () => { fs.writeFileSync(filename, '{"external":true}\n'); }), /changed outside/);
    assert.deepEqual(JSON.parse(fs.readFileSync(filename, 'utf8')), {external: true});
    fs.writeFileSync(filename + '.manager-runtime.lock', 'other writer');
    assert.throws(() => atomicUpdate(filename, old => old), /EEXIST/);
    assert.equal(fs.readFileSync(filename + '.manager-runtime.lock', 'utf8'), 'other writer');
    fs.unlinkSync(filename + '.manager-runtime.lock');
    const link = path.join(dir, 'link.json');
    fs.symlinkSync(filename, link);
    assert.throws(() => atomicUpdate(link, old => old), /symlink/);
    const dangling = path.join(dir, 'dangling.json');
    fs.symlinkSync(path.join(dir, 'missing.json'), dangling);
    assert.throws(() => atomicUpdate(dangling, () => initial()), /symlink/);
    assert.deepEqual(fs.readdirSync(dir).sort(), ['dangling.json', 'link.json', 'runtime.json']);
  } finally { fs.rmSync(dir, {recursive: true}); }
});


test('CLI initializes an isolated ignored registry and qualifies/registers an isolated monitor', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manager-runtime-cli-'));
  const script = fileURLToPath(new URL('../scripts/manager-runtime.mjs', import.meta.url));
  try {
    execFileSync('git', ['init', '--quiet', dir]);
    execFileSync('git', ['-C', dir, 'remote', 'add', 'origin', 'https://example.invalid/project.git']);
    fs.writeFileSync(path.join(dir, '.gitignore'), 'runtime.json\n');
    const assignment = path.join(dir, 'run.json');
    fs.writeFileSync(assignment, '{}');
    const settings = path.join(dir, 'settings.json');
    fs.writeFileSync(settings, JSON.stringify(options.settings));
    const currentRun = path.join(dir, 'current-run.json');
    fs.writeFileSync(currentRun, JSON.stringify({...options.currentRun, record: assignment}));
    const client = writeClientFixture(dir);
    const common = ['--client', client, '--paseo-id', 'fixture-paseo', '--native-id', 'fixture-native'];
    const env = {...process.env, PASEO_HOST: ''};
    const run = argv => JSON.parse(execFileSync(process.execPath, [script, ...argv], {cwd: dir, encoding: 'utf8', env}));
    const inspected = run(['inspect', ...common]);
    assert.equal(inspected.agent.id, 'fixture-native');
    assert.equal(inspected.agent.role, undefined);
    const register = ['register', ...common, '--name', 'Manager', '--assignment', assignment, '--status', 'running', '--settings', settings, '--current-run', currentRun];
    assert.equal(run(register).changed, true);
    assert.equal(run(register).changed, false);
    const runtimeFile = path.join(dir, 'runtime.json');
    assert.equal(fs.statSync(runtimeFile).mode & 0o777, 0o600);
    const before = fs.readFileSync(runtimeFile, 'utf8');
    const rejected = spawnSync(process.execPath, [script, 'register', ...common, '--name', 'Manager', '--assignment', assignment, '--status', 'retired'], {cwd: dir, encoding: 'utf8', env});
    assert.notEqual(rejected.status, 0);
    assert.equal(fs.readFileSync(runtimeFile, 'utf8'), before);
    const watchdog = path.join(dir, 'fake-watchdog.mjs');
    fs.writeFileSync(watchdog, `export function validateRuntime(runtime,entry) {if(runtime.agents[0].paseo!==entry.paseoId) throw Error('wrong Manager');} export function validateConfig(c) {if(!c.managers.length) throw Error('empty');}`);
    const config = path.join(dir, 'watchdog.json');
    fs.writeFileSync(config, JSON.stringify({intervalSeconds: 777, managers: [other]}));
    const monitorArgs = ['monitor', ...common, '--watchdog', watchdog, '--config', config];
    assert.equal(run(monitorArgs).changed, true);
    assert.equal(run(monitorArgs).changed, false);
    const registered = JSON.parse(fs.readFileSync(config));
    assert.equal(registered.intervalSeconds, 777);
    assert.deepEqual(registered.managers, [other, {paseoId: 'fixture-paseo', runtimeFile}]);
    assert.equal(run(['unmonitor', '--config', config, '--runtime', runtimeFile, '--paseo-id', 'fixture-paseo']).changed, true);
    assert.deepEqual(JSON.parse(fs.readFileSync(config)).managers, [other]);
    assert.equal(fs.readdirSync(dir).some(name => name.endsWith('.lock') || name.endsWith('.tmp')), false);
  } finally { fs.rmSync(dir, {recursive: true}); }
});

test('daemon selection rejects remote routes before connection or writes and pins supported local endpoints', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manager-runtime-endpoint-'));
  const script = fileURLToPath(new URL('../scripts/manager-runtime.mjs', import.meta.url));
  try {
    execFileSync('git', ['init', '--quiet', dir]);
    const client = writeClientFixture(dir);
    const common = ['--client', client, '--paseo-id', 'fixture-paseo', '--native-id', 'fixture-native'];
    const config = path.join(dir, 'config.json');
    const originalConfig = JSON.stringify({intervalSeconds: 600, managers: [other]});
    fs.writeFileSync(config, originalConfig);
    const run = (command, host) => spawnSync(process.execPath,
      [script, command, ...common, '--config', config],
      {cwd: dir, encoding: 'utf8', env: {...process.env, PASEO_HOST: host}, timeout: 10000});
    const remoteHosts = ['ssh://fixture-remote', 'https://relay.example/offer?token=fixture-secret',
      'tcp://198.51.100.8:6767?password=fixture-secret', 'fixture-remote:6767',
      '0.0.0.0:6767', '[::]:6767', String.raw`pipe://\\fixture-remote\pipe\daemon`];
    for (const host of remoteHosts) {
      for (const command of ['register', 'monitor']) {
        const result = run(command, host);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /local IPC or loopback/);
        assert.equal(result.stderr.includes(host), false);
        assert.equal(result.stderr.includes('fixture-secret'), false);
        assert.equal(fs.existsSync(path.join(dir, 'connected')), false);
        assert.equal(fs.existsSync(path.join(dir, 'runtime.json')), false);
        assert.equal(fs.readFileSync(config, 'utf8'), originalConfig);
        assert.equal(fs.readdirSync(dir).some(name => name.endsWith('.lock') || name.endsWith('.tmp')), false);
      }
    }
    const localHosts = ['', 'localhost:6767', '127.0.0.1:6767', '127.9.8.7:6767', '[::1]:6767',
      'tcp://127.0.0.1:6767?ssl=true&password=fixture-secret', 'unix:///tmp/fixture.sock',
      '/tmp/fixture.sock', String.raw`pipe://\\.\pipe\daemon`];
    for (const host of localHosts) {
      const result = run('inspect', host);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(JSON.parse(result.stdout).agent.id, 'fixture-native');
      assert.equal(fs.readFileSync(path.join(dir, 'connected'), 'utf8'), 'explicit selected endpoint');
      fs.unlinkSync(path.join(dir, 'connected'));
    }
  } finally { fs.rmSync(dir, {recursive: true}); }
});
