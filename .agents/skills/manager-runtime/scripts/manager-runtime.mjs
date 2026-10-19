#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {isIP} from 'node:net';
import {execFileSync} from 'node:child_process';
import {pathToFileURL, fileURLToPath} from 'node:url';

const states = new Set(['running', 'idle', 'waiting']);
const json = value => JSON.stringify(value, null, 2) + '\n';
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const requireText = (value, label) => {
  if (typeof value !== 'string' || !value.trim()) throw Error(label + ' is required');
  return value;
};
const absolute = value => {
  requireText(value, 'absolute path');
  if (!path.isAbsolute(value)) throw Error('Use an absolute path: ' + value);
  return path.normalize(value);
};

// One short-lived writer lock per file; never wait on or remove another writer's lock.
export function atomicUpdate(filename, update, beforeRename = () => {}) {
  filename = absolute(filename);
  const lock = filename + '.manager-runtime.lock';
  const lockFd = fs.openSync(lock, 'wx', 0o600);
  let temp;
  try {
    if (fs.lstatSync(filename, {throwIfNoEntry: false})?.isSymbolicLink()) throw Error('Refusing symlink: ' + filename);
    const original = fs.existsSync(filename) ? fs.readFileSync(filename, 'utf8') : null;
    const previous = original === null ? null : JSON.parse(original);
    const next = update(previous);
    if (same(previous, next)) {
      if (original !== null) fs.chmodSync(filename, 0o600);
      return {changed: false, value: next};
    }
    temp = filename + '.' + crypto.randomUUID() + '.tmp';
    const fd = fs.openSync(temp, 'wx', 0o600);
    try { fs.writeFileSync(fd, json(next)); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    beforeRename();
    const current = fs.existsSync(filename) ? fs.readFileSync(filename, 'utf8') : null;
    if (current !== original) throw Error('File changed outside writer lock; reconcile before retrying');
    fs.renameSync(temp, filename);
    temp = null;
    const dir = fs.openSync(path.dirname(filename), 'r');
    try { fs.fsyncSync(dir); } finally { fs.closeSync(dir); }
    return {changed: true, value: next};
  } finally {
    if (temp) fs.unlinkSync(temp);
    fs.closeSync(lockFd);
    fs.unlinkSync(lock);
  }
}

const timestamp = (value, label) => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(value) || !Number.isFinite(Date.parse(value))) throw Error('Invalid timestamp: ' + label);
};
const records = (value, label) => {
  if (!Array.isArray(value)) throw Error('Missing array: ' + label);
  const ids = new Set();
  for (const record of value) {
    const id = requireText(record?.id, label + '.id');
    if (ids.has(id)) throw Error('Duplicate ' + label + ' ID');
    ids.add(id);
  }
  return ids;
};

export function validateRuntime(runtime) {
  if (runtime?.schemaVersion !== 2 || !Number.isInteger(runtime.revision) || runtime.revision < 1) throw Error('Unsupported runtime schema/revision; preserve older schema before explicit conversion');
  timestamp(runtime.updatedAt, 'updatedAt');
  for (const field of ['project', 'settings']) if (!runtime[field] || typeof runtime[field] !== 'object' || Array.isArray(runtime[field])) throw Error('Missing ' + field);
  for (const key of ['name', 'repository', 'paseoId']) requireText(runtime.project[key], 'project.' + key);
  absolute(runtime.project.root);
  if (!Number.isFinite(runtime.settings.guardApprovalTimeoutSeconds) || runtime.settings.guardApprovalTimeoutSeconds <= 0) throw Error('guardApprovalTimeoutSeconds must be positive');
  if (!Object.hasOwn(runtime, 'currentVersion')) throw Error('Missing currentVersion');
  if (runtime.currentVersion !== null) {
    const version = runtime.currentVersion;
    for (const key of ['id', 'branch']) requireText(version?.[key], 'currentVersion.' + key);
    absolute(version.plan); absolute(version.record);
    if (!['planned', 'implementing', 'completed'].includes(version.status)) throw Error('Invalid currentVersion status');
  }
  if (!Array.isArray(runtime.agents)) throw Error('Missing agents');
  const identities = new Set(), names = new Set(), bindings = new Map();
  for (const agent of runtime.agents) {
    for (const key of ['role', 'scope', 'host', 'runtime', 'id', 'name', 'paseo', 'workspace']) requireText(agent[key], key);
    if (!['Manager', 'Worker', 'Reviewer'].includes(agent.role)) throw Error('Invalid participant role');
    if (!states.has(agent.status) || Object.keys(agent).some(k => k.toLowerCase() === 'lifecycle')) throw Error('Invalid expected status/lifecycle');
    absolute(agent.cwd); absolute(agent.assignment);
    for (const key of ['model', 'effort', 'mode']) if (agent[key] !== null) requireText(agent[key], key);
    const identity = JSON.stringify([agent.host, agent.runtime, agent.id]);
    const name = JSON.stringify([agent.role, agent.name]);
    if (identities.has(identity) || names.has(name) || bindings.has(agent.paseo)) throw Error('Duplicate participant identity/name/binding');
    identities.add(identity); names.add(name); bindings.set(agent.paseo, agent);
  }
  if (runtime.agents.filter(a => a.role === 'Manager').length !== 1) throw Error('Exactly one Manager is required');
  const missions = records(runtime.missions, 'missions');
  for (const mission of runtime.missions) {
    for (const key of ['issue', 'branch', 'activity']) requireText(mission[key], 'mission.' + key);
    absolute(mission.worktree); timestamp(mission.updatedAt, 'mission.updatedAt');
    if (!['implementing', 'reviewing', 'waiting'].includes(mission.status)) throw Error('Invalid mission status');
    if (bindings.get(mission.worker)?.role !== 'Worker' ||
        (mission.reviewer !== null && bindings.get(mission.reviewer)?.role !== 'Reviewer') ||
        (mission.status === 'reviewing' && mission.reviewer === null)) throw Error('Mission requires role-correct Worker/Reviewer bindings');
  }
  records(runtime.reservations, 'reservations');
  const resources = new Set();
  for (const reservation of runtime.reservations) {
    requireText(reservation.resource, 'reservation.resource');
    if (resources.has(reservation.resource) || !missions.has(reservation.mission) || !bindings.has(reservation.holder)) throw Error('Invalid/duplicate reservation reference');
    resources.add(reservation.resource);
    if (!Array.isArray(reservation.operations)) throw Error('Reservation operations required');
    for (const operation of reservation.operations) requireText(operation, 'reservation operation');
    timestamp(reservation.acquiredAt, 'reservation.acquiredAt');
  }
  records(runtime.guardRequests, 'guardRequests');
  for (const request of runtime.guardRequests) {
    for (const key of ['proposal', 'reason']) requireText(request[key], 'guardRequest.' + key);
    if (request.mission !== null) requireText(request.mission, 'guardRequest.mission');
    if (!['pending', 'approved', 'denied'].includes(request.status) || ![null, 'owner', 'timeout'].includes(request.resolution) || typeof request.askOnNextWatchdog !== 'boolean') throw Error('Invalid guard decision');
    timestamp(request.requestedAt, 'guardRequest.requestedAt'); timestamp(request.deadline, 'guardRequest.deadline');
    if (Date.parse(request.deadline) <= Date.parse(request.requestedAt)) throw Error('Invalid guard deadline');
    if (request.status === 'pending') {
      if ((request.mission !== null && !missions.has(request.mission)) || request.resolution !== null || request.decisionAt !== null || request.dismissedAt !== null) throw Error('Invalid pending guard proposal');
    } else {
      timestamp(request.decisionAt, 'guardRequest.decisionAt');
      if (request.dismissedAt !== null) timestamp(request.dismissedAt, 'guardRequest.dismissedAt');
      if (request.resolution === 'timeout') {
        if (request.status !== 'denied' || request.dismissedAt === null) throw Error('Timeout is a dismissed denial');
      } else if (request.resolution !== 'owner' || !request.ownerDecision) throw Error('Owner decision reference required');
    }
  }
  records(runtime.credentials, 'credentials');
  for (const credential of runtime.credentials) {
    for (const key of ['kind', 'host', 'ownerRequest', 'versionId']) requireText(credential[key], 'credential.' + key);
    const relative = path.relative(runtime.project.root, absolute(credential.path));
    if (!relative || (!relative.startsWith('..' + path.sep) && !path.isAbsolute(relative))) throw Error('Credential reference must be outside repository');
    timestamp(credential.createdAt, 'credential.createdAt');
    if (!['pending-owner-decision', 'retained', 'destroyed'].includes(credential.cleanupStatus)) throw Error('Invalid credential cleanupStatus');
  }
  if (!Array.isArray(runtime.history)) throw Error('Missing history');
  for (const reference of runtime.history) requireText(reference, 'history reference');
  return runtime;
}

export function verifyBinding(raw, workspaces, {root, paseoId, nativeId, host}) {
  const agent = raw.agent;
  const native = agent?.runtimeInfo?.sessionId ?? agent?.persistence?.sessionId;
  const ws = workspaces.find(w => w.id === agent?.workspaceId);
  if (!agent || agent.id !== paseoId || native !== nativeId) throw Error('Native/Paseo identity mismatch');
  if (agent.cwd !== root || ws?.workspaceDirectory !== root || ws.projectRootPath !== root ||
      ws.projectId !== raw.project?.projectKey) throw Error('Project/workspace/root mismatch');
  if (agent.archivedAt || ws.archivingAt || agent.pendingPermissions?.length || agent.attentionReason === 'error') throw Error('Agent is archived, gated or unavailable');
  for (const value of [agent.provider, agent.model, agent.effectiveThinkingOptionId, agent.runtimeInfo?.modeId]) requireText(value, 'effective profile');
  return {
    project: {name: requireText(raw.project.projectName, 'project name'), root, paseoId: ws.projectId},
    agent: {host,
      runtime: agent.provider, id: native, paseo: paseoId, model: agent.model,
      workspace: ws.id, cwd: root, effort: agent.effectiveThinkingOptionId, mode: agent.runtimeInfo.modeId},
  };
}

export function reconcileRuntime(previous, binding, options) {
  const {name, assignment, status, settings, currentVersion, handoff} = options;
  requireText(name, 'Manager name'); absolute(assignment);
  if (!states.has(status)) throw Error('Choose expected running, idle or waiting');
  if (!previous && (!settings || typeof settings !== 'object' || Array.isArray(settings))) throw Error('Initial settings must be an object');
  const next = previous ? structuredClone(validateRuntime(previous)) : {
    schemaVersion: 2, revision: 1, updatedAt: new Date().toISOString(),
    project: {...binding.project, repository: requireText(options.repository, 'repository')},
    settings: {guardApprovalTimeoutSeconds: 600, ...settings}, currentVersion: currentVersion ?? null,
    agents: [], missions: [], reservations: [], guardRequests: [], credentials: [], history: [],
  };
  if (next.project.root !== binding.project.root || next.project.paseoId !== binding.project.paseoId) throw Error('Existing registry belongs to another project/root');
  const existing = next.agents.find(a => a.role === 'Manager');
  if (existing && (existing.host !== binding.agent.host || existing.runtime !== binding.agent.runtime || existing.id !== binding.agent.id)) {
    if (!handoff || !same(handoff.previousManager, existing) || !same(handoff.successor, {host: binding.agent.host, runtime: binding.agent.runtime, id: binding.agent.id, paseo: binding.agent.paseo}) || !handoff.record) throw Error('Conflicting Manager: require exact recorded handoff');
    next.agents.splice(next.agents.indexOf(existing), 1);
  }
  const current = next.agents.find(a => a.role === 'Manager') ?? next.agents.find(a =>
    a.host === binding.agent.host && a.runtime === binding.agent.runtime && a.id === binding.agent.id);
  const participant = {...current, ...binding.agent, role: 'Manager',
    scope: current?.role === 'Manager' ? current.scope : 'Project-wide coordination', name, assignment, status};
  if (current) next.agents[next.agents.indexOf(current)] = participant;
  else next.agents.push(participant);
  if (currentVersion !== undefined) next.currentVersion = currentVersion && currentVersion.id === next.currentVersion?.id
    ? {...next.currentVersion, ...currentVersion} : currentVersion;
  // Operational records, settings and extension fields remain intact.
  validateRuntime(next);
  if (previous && !same(previous, next)) {
    next.revision = previous.revision + 1;
    next.updatedAt = new Date().toISOString();
  }
  return next;
}

async function observedBinding(args) {
  const root = fs.realpathSync(execFileSync('git', ['rev-parse', '--show-toplevel'], {encoding: 'utf8'}).trim());
  const paseoId = requireText(args['paseo-id'] ?? process.env.PASEO_AGENT_ID, 'Paseo ID');
  const nativeId = requireText(args['native-id'] ?? process.env.CODEX_THREAD_ID, 'native ID');
  const {connectToDaemon, getDaemonHost, resolveDaemonTarget} = await import(pathToFileURL(absolute(args.client)).href);
  let host;
  try {
    host = getDaemonHost();
    const target = resolveDaemonTarget(host);
    const address = target.type === 'tcp' ? new URL(target.url).hostname.replace(/^\[|\]$/g, '') : null;
    const localIpc = target.type === 'ipc' && target.socketPath &&
      (!target.socketPath.startsWith('\\\\') || target.socketPath.startsWith('\\\\.\\pipe\\'));
    if (!localIpc && address !== 'localhost' && address !== '::1' &&
        !(isIP(address ?? '') === 4 && address.startsWith('127.'))) throw Error();
  } catch { throw Error('A local IPC or loopback Paseo daemon endpoint is required'); }
  // Pin the checked endpoint so inherited environment/default fallback cannot select another daemon.
  let client;
  try { client = await connectToDaemon({host, timeout: 10000}); }
  catch { throw Error('Cannot connect to the selected local Paseo daemon'); }
  try {
    const raw = await client.fetchAgent({agentId: paseoId});
    const pages = [], workspaces = [];
    let cursor;
    do {
      const page = await client.fetchWorkspaces({page: {limit: 200, ...(cursor ? {cursor} : {})}});
      workspaces.push(...page.entries);
      cursor = page.pageInfo?.nextCursor;
      if (cursor && pages.includes(cursor)) throw Error('Repeated workspace cursor');
      pages.push(cursor);
    } while (cursor);
    return verifyBinding(raw, workspaces, {root, paseoId, nativeId, host: os.hostname()});
  } finally { await client.close(); }
}

async function main() {
  const [command, ...argv] = process.argv.slice(2);
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    if (!argv[i].startsWith('--') || argv[i + 1] === undefined) throw Error('Use --option value pairs');
    const key = argv[i].slice(2);
    if (Object.hasOwn(args, key)) throw Error('Duplicate option: ' + key);
    args[key] = argv[i + 1];
  }
  const allowed = new Set(['paseo-id', 'native-id', 'client', 'name', 'assignment', 'status', 'settings', 'current-version', 'handoff']);
  for (const key of Object.keys(args)) if (!allowed.has(key)) throw Error('Unknown option: ' + key);
  const read = filename => JSON.parse(fs.readFileSync(absolute(filename), 'utf8'));
  if (!['inspect', 'register'].includes(command)) throw Error('Commands: inspect, register');
  const binding = await observedBinding(args);
  if (command === 'inspect') { console.log(json(binding)); return; }
  const runtimeFile = path.join(binding.project.root, 'runtime.json');
  execFileSync('git', ['check-ignore', '--quiet', runtimeFile]);
  if (execFileSync('git', ['ls-files', '--', runtimeFile], {encoding: 'utf8'}).trim()) throw Error('runtime.json must be untracked');
  const assignment = absolute(args.assignment);
  fs.accessSync(assignment, fs.constants.R_OK);
  const handoff = args.handoff ? read(args.handoff) : undefined;
  if (handoff) fs.accessSync(absolute(handoff.record), fs.constants.R_OK);
  const result = atomicUpdate(runtimeFile, old => {
    if (!old && (!args.settings || !args['current-version'])) throw Error('Initialize with explicit settings and current-version JSON files');
    return reconcileRuntime(old, binding, {
      name: args.name, assignment, status: args.status, repository: execFileSync('git', ['remote', 'get-url', 'origin'], {encoding: 'utf8'}).trim(),
      settings: args.settings ? read(args.settings) : undefined,
      currentVersion: args['current-version'] ? read(args['current-version']) : undefined, handoff,
    });
  });
  console.log(json({changed: result.changed, runtimeFile, revision: result.value.revision}));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error('manager-runtime: ' + error.message); process.exitCode = 1; });
}
