#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {pathToFileURL, fileURLToPath} from 'node:url';

const states = new Set(['running', 'idle', 'waiting']);
const json = value => JSON.stringify(value, null, 2) + '\n';
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const requireText = (value, label) => {
  if (typeof value !== 'string' || !value.trim()) throw Error(label + ' is required');
  return value;
};
const configPath = value => absolute(value?.startsWith('~/') ? path.join(os.homedir(), value.slice(2)) : value);
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

export function validateRuntime(runtime) {
  if (runtime?.schemaVersion !== 1 || !Number.isInteger(runtime.revision) || runtime.revision < 1) throw Error('Unsupported runtime schema/revision');
  for (const field of ['project', 'settings']) if (!runtime[field] || typeof runtime[field] !== 'object' || Array.isArray(runtime[field])) throw Error('Missing ' + field);
  if (!Object.hasOwn(runtime, 'currentRun') || !Array.isArray(runtime.agents)) throw Error('Missing currentRun/agents');
  if (runtime.currentRun !== null) {
    if (typeof runtime.currentRun !== 'object' || Array.isArray(runtime.currentRun)) throw Error('currentRun must be an object or null');
    requireText(runtime.currentRun.id, 'currentRun.id'); absolute(runtime.currentRun.record);
  }
  requireText(runtime.project.paseoId, 'project.paseoId');
  absolute(runtime.project.root);
  const identities = new Set(), names = new Set(), bindings = new Set();
  for (const agent of runtime.agents) {
    for (const key of ['role', 'scope', 'host', 'runtime', 'id', 'name']) requireText(agent[key], key);
    if (!states.has(agent.status) || Object.keys(agent).some(k => k.toLowerCase() === 'lifecycle')) throw Error('Invalid expected status/lifecycle');
    const identity = JSON.stringify([agent.host, agent.runtime, agent.id]);
    const name = JSON.stringify([agent.role, agent.name]);
    if (identities.has(identity) || names.has(name)) throw Error('Duplicate participant identity/name');
    identities.add(identity); names.add(name);
    if (agent.paseo) {
      if (bindings.has(agent.paseo) || !agent.workspace) throw Error('Duplicate/invalid Paseo binding');
      absolute(agent.cwd); bindings.add(agent.paseo);
    } else if (agent.runtime !== 'chatgpt') throw Error('Local participant requires Paseo binding');
  }
  if (runtime.agents.filter(a => a.role === 'Manager').length !== 1) throw Error('Exactly one Manager is required');
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
  const {name, assignment, status, settings, currentRun, handoff} = options;
  requireText(name, 'Manager name'); absolute(assignment);
  if (!states.has(status)) throw Error('Choose expected running, idle or waiting');
  const next = previous ? structuredClone(validateRuntime(previous)) : {
    schemaVersion: 1, revision: 1, updatedAt: new Date().toISOString(),
    project: {...binding.project, repository: requireText(options.repository, 'repository')},
    settings: settings ?? {}, currentRun: currentRun ?? null, agents: [],
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
  // Existing settings, pending requests, current run and extension fields remain intact.
  validateRuntime(next);
  if (previous && !same(previous, next)) {
    next.revision = previous.revision + 1;
    next.updatedAt = new Date().toISOString();
  }
  return next;
}

export function reconcileConfig(previous, entry, {replaceId, remove = false} = {}) {
  if (!previous || !Number.isInteger(previous.intervalSeconds) || previous.intervalSeconds < 1 || !Array.isArray(previous.managers)) throw Error('Existing supported watchdog config required');
  const next = structuredClone(previous);
  const ids = new Set(), files = new Set();
  for (const item of next.managers) {
    if (!/^[A-Za-z0-9_-]+$/.test(item.paseoId) || ids.has(item.paseoId) || files.has(item.runtimeFile)) throw Error('Invalid/duplicate watchdog targets');
    const runtimeFile = configPath(item.runtimeFile);
    if (files.has(runtimeFile)) throw Error('Duplicate watchdog runtime path');
    ids.add(item.paseoId); files.add(runtimeFile);
  }
  if (!/^[A-Za-z0-9_-]+$/.test(entry.paseoId)) throw Error('Invalid Paseo ID');
  absolute(entry.runtimeFile);
  const found = next.managers.find(m => m.paseoId === entry.paseoId || configPath(m.runtimeFile) === entry.runtimeFile);
  if (remove) {
    if (found && (found.paseoId !== entry.paseoId || configPath(found.runtimeFile) !== entry.runtimeFile)) throw Error('Retirement target mismatch');
    if (found) next.managers.splice(next.managers.indexOf(found), 1);
    if (!next.managers.length) throw Error('Last monitor requires explicit service retirement; no empty supported config');
  } else if (found) {
    if (found.paseoId === entry.paseoId && configPath(found.runtimeFile) === entry.runtimeFile) return next;
    if (configPath(found.runtimeFile) !== entry.runtimeFile || found.paseoId !== replaceId) throw Error('Conflicting monitor: require exact predecessor ID');
    next.managers[next.managers.indexOf(found)] = {...found, ...entry};
  } else {
    if (replaceId) throw Error('Predecessor monitor not found');
    next.managers.push(entry);
  }
  return next;
}

async function observedBinding(args) {
  const root = fs.realpathSync(execFileSync('git', ['rev-parse', '--show-toplevel'], {encoding: 'utf8'}).trim());
  const paseoId = requireText(args['paseo-id'] ?? process.env.PASEO_AGENT_ID, 'Paseo ID');
  const nativeId = requireText(args['native-id'] ?? process.env.CODEX_THREAD_ID, 'native ID');
  const {connectToDaemon} = await import(pathToFileURL(absolute(args.client)).href);
  // Local daemon only: the installed watchdog also observes its own local daemon.
  const client = await connectToDaemon({timeout: 10000});
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
  const allowed = new Set(['paseo-id', 'native-id', 'client', 'name', 'assignment', 'status', 'settings', 'current-run', 'handoff', 'config', 'runtime', 'watchdog', 'replace-id']);
  for (const key of Object.keys(args)) if (!allowed.has(key)) throw Error('Unknown option: ' + key);
  const read = filename => JSON.parse(fs.readFileSync(absolute(filename), 'utf8'));
  if (!['inspect', 'register', 'monitor', 'unmonitor'].includes(command)) throw Error('Commands: inspect, register, monitor, unmonitor');
  if (command === 'unmonitor') {
    const result = atomicUpdate(args.config, old => reconcileConfig(old, {paseoId: requireText(args['paseo-id'], 'Paseo ID'), runtimeFile: absolute(args.runtime)}, {remove: true}));
    console.log(json({changed: result.changed})); return;
  }
  const binding = await observedBinding(args);
  if (command === 'inspect') { console.log(json(binding)); return; }
  const runtimeFile = path.join(binding.project.root, 'runtime.json');
  if (command === 'register') {
    execFileSync('git', ['check-ignore', '--quiet', runtimeFile]);
    if (execFileSync('git', ['ls-files', '--', runtimeFile], {encoding: 'utf8'}).trim()) throw Error('runtime.json must be untracked');
    const assignment = absolute(args.assignment);
    fs.accessSync(assignment, fs.constants.R_OK);
    const handoff = args.handoff ? read(args.handoff) : undefined;
    if (handoff) fs.accessSync(absolute(handoff.record), fs.constants.R_OK);
    const result = atomicUpdate(runtimeFile, old => {
      if (!old && (!args.settings || !args['current-run'])) throw Error('Initialize with explicit settings and current-run JSON files');
      return reconcileRuntime(old, binding, {
      name: args.name, assignment, status: args.status, repository: execFileSync('git', ['remote', 'get-url', 'origin'], {encoding: 'utf8'}).trim(),
      settings: args.settings ? read(args.settings) : undefined,
      currentRun: args['current-run'] ? read(args['current-run']) : undefined, handoff,
      });
    });
    console.log(json({changed: result.changed, runtimeFile, revision: result.value.revision})); return;
  }
  const runtime = validateRuntime(read(runtimeFile));
  const manager = runtime.agents.find(a => a.role === 'Manager');
  if (manager.host !== os.hostname() || manager.paseo !== binding.agent.paseo || manager.id !== binding.agent.id) throw Error('Watchdog host/Manager binding mismatch');
  const watchdog = await import(pathToFileURL(absolute(args.watchdog)).href);
  const entry = {paseoId: manager.paseo, runtimeFile};
  watchdog.validateRuntime(runtime, entry);
  const result = atomicUpdate(args.config, old => {
    const next = reconcileConfig(old, entry, {replaceId: args['replace-id']});
    watchdog.validateConfig(next);
    return next;
  });
  console.log(json({changed: result.changed, runtimeFile, paseoId: manager.paseo, readiness: 'Run installed watchdog --check and inspect units'}));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error('manager-runtime: ' + error.message); process.exitCode = 1; });
}
