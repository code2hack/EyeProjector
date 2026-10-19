import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test} from 'node:test';
import {execFileSync, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {atomicUpdate, verifyBinding, validateRuntime, reconcileRuntime} from '../scripts/manager-runtime.mjs';

const root = '/tmp/manager-runtime-fixture';
const binding = {project: {name: 'Fixture', root, paseoId: 'prj_fixture'}, agent: {
  host: 'fixture-host', runtime: 'codex',
  id: 'native-1', paseo: 'paseo-1', model: 'fixture-model', workspace: 'wks_fixture',
  cwd: root, effort: 'high', mode: 'full-access',
}};
const options = {name: 'Manager', assignment: '/tmp/fixture-run/run.json', status: 'running',
  repository: 'example/fixture', settings: {notification: 'written', concurrencyDecision: '/tmp/decision'},
  currentVersion: {id: 'version-001', branch: 'version/fixture', plan: '/tmp/fixture-run/plan.md', record: '/tmp/fixture-run/run.json', status: 'planned'}};
const initial = () => reconcileRuntime(null, binding, options);

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
  assert.equal(value.schemaVersion, 2);
  assert.equal(value.revision, 1);
  assert.equal(value.project.root, root);
  assert.equal(value.agents[0].id, 'native-1');
  assert.equal(value.agents[0].paseo, 'paseo-1');
  assert.deepEqual(reconcileRuntime(value, binding, options), value);
  value.pendingRequests = [{id: 'request-1', state: 'unknown'}];
  value.agents[0].attempts = [{todo: 'todo-1', count: 3}];
  const next = reconcileRuntime(value, {...binding, agent: {...binding.agent, model: 'new-model'}}, {...options, status: 'waiting', settings: {discard: true}, currentVersion: undefined});
  assert.equal(next.revision, 2);
  assert.equal(next.agents[0].status, 'waiting');
  assert.deepEqual(next.settings, value.settings);
  assert.equal(next.settings.guardApprovalTimeoutSeconds, 600);
  assert.deepEqual(next.currentVersion, value.currentVersion);
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

test('handoff replaces only recorded Manager, retaining version, children and history references', () => {
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
  assert.deepEqual(result.currentVersion, value.currentVersion);
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

test('formal version state updates preserve operational records and older schema requires explicit conversion', () => {
  const value = initial();
  assert.equal(value.settings.guardApprovalTimeoutSeconds, 600);
  assert.equal(reconcileRuntime(null, binding, {...options, settings: {guardApprovalTimeoutSeconds: 90}}).settings.guardApprovalTimeoutSeconds, 90);
  for (const key of ['missions', 'reservations', 'guardRequests', 'credentials', 'history']) assert.deepEqual(value[key], []);
  const worker = {...value.agents[0], role: 'Worker', name: 'Worker', id: 'worker-native', paseo: 'worker-paseo', assignment: '/tmp/worker-mission.json'};
  const reviewer = {...worker, role: 'Reviewer', name: 'Reviewer', id: 'reviewer-native', paseo: 'reviewer-paseo', assignment: '/tmp/review-mission.json'};
  value.agents.push(worker, reviewer);
  value.missions.push({id: 'mission-1', issue: 'https://example.invalid/issues/1', worker: worker.paseo, reviewer: reviewer.paseo, branch: 'work/issue-1', worktree: '/tmp/issue-1', status: 'reviewing', activity: 'Review exact candidate', updatedAt: value.updatedAt});
  value.reservations.push({id: 'phone-reservation', resource: 'phone-fixture', mission: 'mission-1', holder: worker.paseo, operations: ['inspect'], acquiredAt: value.updatedAt});
  value.guardRequests.push({id: 'guard-1', mission: 'mission-1', proposal: 'Proposed check', reason: 'Explain actual purpose', requestedAt: '2026-10-19T00:00:00Z', deadline: '2026-10-19T00:10:00Z', status: 'pending', decisionAt: null, resolution: null, dismissedAt: null, askOnNextWatchdog: false});
  value.credentials.push({id: 'credential-1', kind: 'fixture', host: 'fixture-host', path: '/tmp/private-credentials/fixture', ownerRequest: 'owner-retention-request', createdAt: value.updatedAt, versionId: 'version-001', cleanupStatus: 'pending-owner-decision'});
  value.history.push('/tmp/fixture-run/decision.json');
  value.currentVersion.extension = {retained: true};
  const {extension, ...fields} = value.currentVersion;
  const version = {...fields, status: 'implementing'};
  const next = reconcileRuntime(value, binding, {...options, currentVersion: version});
  assert.deepEqual(next.currentVersion, {...version, extension});
  for (const key of ['missions', 'reservations', 'guardRequests', 'credentials', 'history']) assert.deepEqual(next[key], value[key]);
  assert.equal(next.guardRequests[0].status, 'pending'); // Manager adjudicates deadlines; registration does not schedule decisions.
  assert.deepEqual(next.agents.find(a => a.paseo === worker.paseo), worker);
  assert.equal(next.revision, value.revision + 1);
  assert.equal(reconcileRuntime(next, binding, {...options, currentVersion: null}).currentVersion, null);
  const invalidWorker = structuredClone(value);
  invalidWorker.missions[0].worker = reviewer.paseo;
  assert.throws(() => validateRuntime(invalidWorker), /role-correct/);
  const duplicateResource = structuredClone(value);
  duplicateResource.reservations.push({...value.reservations[0], id: 'other-reservation'});
  assert.throws(() => validateRuntime(duplicateResource), /reservation/);
  const insideRepository = structuredClone(value);
  insideRepository.credentials[0].path = root + '/secret';
  assert.throws(() => validateRuntime(insideRepository), /outside repository/);
  const badRole = structuredClone(value);
  badRole.agents[1].role = 'OtherRole';
  assert.throws(() => validateRuntime(badRole), /participant role/);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manager-runtime-old-schema-'));
  try {
    const filename = path.join(dir, 'runtime.json');
    const old = {...value, schemaVersion: 1, currentRun: {record: '/tmp/older-run.json'}};
    fs.writeFileSync(filename, JSON.stringify(old));
    const original = fs.readFileSync(filename, 'utf8');
    assert.throws(() => atomicUpdate(filename, previous => reconcileRuntime(previous, binding, options)), /explicit conversion/);
    assert.equal(fs.readFileSync(filename, 'utf8'), original);
    assert.deepEqual(fs.readdirSync(dir), ['runtime.json']);
  } finally { fs.rmSync(dir, {recursive: true}); }
});

test('timeout guard denial is dismissed and retained for a new correlated question', () => {
  const value = initial();
  const denied = {id: 'guard-1', mission: 'finished-mission', proposal: 'Proposed check', reason: 'Purpose', requestedAt: '2026-10-19T00:00:00Z', deadline: '2026-10-19T00:10:00Z', status: 'denied', decisionAt: '2026-10-19T00:10:01Z', resolution: 'timeout', dismissedAt: '2026-10-19T00:10:01Z', askOnNextWatchdog: true};
  value.guardRequests.push(denied);
  value.history.push('/tmp/finished-mission.json');
  assert.equal(validateRuntime(value), value);
  const nextQuestion = {...denied, id: 'guard-2', mission: null, status: 'pending', decisionAt: null, resolution: null, dismissedAt: null, askOnNextWatchdog: false, inReplyTo: 'guard-1'};
  value.guardRequests.push(nextQuestion);
  assert.equal(validateRuntime(value), value);
  assert.equal(value.guardRequests[0].status, 'denied');
  const processed = structuredClone(value);
  processed.guardRequests[0].askOnNextWatchdog = false;
  processed.guardRequests[0].followupRequest = 'guard-2';
  assert.equal(validateRuntime(processed), processed);
  for (const key of ['status', 'resolution', 'decisionAt', 'dismissedAt']) assert.equal(processed.guardRequests[0][key], denied[key]);
  const unresolved = structuredClone(value);
  unresolved.guardRequests[0].dismissedAt = null;
  assert.throws(() => validateRuntime(unresolved), /dismissed denial/);
  const invalidApproval = structuredClone(value);
  invalidApproval.guardRequests[0].status = 'approved';
  assert.throws(() => validateRuntime(invalidApproval), /Timeout/);
  const explicit = structuredClone(value);
  explicit.guardRequests[0] = {...denied, resolution: 'owner', ownerDecision: '/tmp/owner-decision.json', askOnNextWatchdog: false};
  assert.equal(validateRuntime(explicit), explicit);
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


test('CLI initializes an isolated ignored registry', () => {
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
    const currentVersion = path.join(dir, 'current-version.json');
    fs.writeFileSync(currentVersion, JSON.stringify({...options.currentVersion, record: assignment}));
    const client = writeClientFixture(dir);
    const common = ['--client', client, '--paseo-id', 'fixture-paseo', '--native-id', 'fixture-native'];
    const env = {...process.env, PASEO_HOST: ''};
    const run = argv => JSON.parse(execFileSync(process.execPath, [script, ...argv], {cwd: dir, encoding: 'utf8', env}));
    const inspected = run(['inspect', ...common]);
    assert.equal(inspected.agent.id, 'fixture-native');
    assert.equal(inspected.agent.role, undefined);
    const register = ['register', ...common, '--name', 'Manager', '--assignment', assignment, '--status', 'running', '--settings', settings, '--current-version', currentVersion];
    assert.equal(run(register).changed, true);
    assert.equal(run(register).changed, false);
    const runtimeFile = path.join(dir, 'runtime.json');
    assert.equal(fs.statSync(runtimeFile).mode & 0o777, 0o600);
    const before = fs.readFileSync(runtimeFile, 'utf8');
    const rejected = spawnSync(process.execPath, [script, 'register', ...common, '--name', 'Manager', '--assignment', assignment, '--status', 'retired'], {cwd: dir, encoding: 'utf8', env});
    assert.notEqual(rejected.status, 0);
    assert.equal(fs.readFileSync(runtimeFile, 'utf8'), before);
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
    const run = (command, host) => spawnSync(process.execPath,
      [script, command, ...common],
      {cwd: dir, encoding: 'utf8', env: {...process.env, PASEO_HOST: host}, timeout: 10000});
    const remoteHosts = ['ssh://fixture-remote', 'https://relay.example/offer?token=fixture-secret',
      'tcp://198.51.100.8:6767?password=fixture-secret', 'fixture-remote:6767',
      '0.0.0.0:6767', '[::]:6767', String.raw`pipe://\\fixture-remote\pipe\daemon`];
    for (const host of remoteHosts) {
      for (const command of ['inspect', 'register']) {
        const result = run(command, host);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /local IPC or loopback/);
        assert.equal(result.stderr.includes(host), false);
        assert.equal(result.stderr.includes('fixture-secret'), false);
        assert.equal(fs.existsSync(path.join(dir, 'connected')), false);
        assert.equal(fs.existsSync(path.join(dir, 'runtime.json')), false);
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
