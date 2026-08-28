'use strict';

const fs = require('fs');
const path = require('path');
const idle = require('./idle');

const DATA_DIR = path.join(__dirname, '..', 'data');
const FILE = path.join(DATA_DIR, 'state.json');
const TMP = FILE + '.tmp';

function blank() {
  return {
    todos: [],
    scene: idle.DEFAULT_SCENE,
    stopwatch: { running: false, startedAt: null, accumulated: 0, laps: [] }
  };
}

let state = blank();
let version = 1;
let waiters = [];
let saveTimer = null;

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    state = Object.assign(blank(), raw);
    if (!Array.isArray(state.todos)) state.todos = [];
    state.stopwatch = Object.assign(blank().stopwatch, state.stopwatch || {});
    if (!Array.isArray(state.stopwatch.laps)) state.stopwatch.laps = [];
    // A scene that no longer ships (renamed, dropped) falls back rather than
    // leaving both screens with nothing to draw.
    if (!idle.isScene(state.scene)) state.scene = idle.DEFAULT_SCENE;
  } catch (err) {
    state = blank();
  }
}

function writeNow() {
  saveTimer = null;
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(TMP, JSON.stringify(state, null, 2));
  fs.renameSync(TMP, FILE);
}

// Coalesce bursts of edits into one disk write.
function scheduleSave() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    try { writeNow(); } catch (err) { console.error('save failed:', err.message); }
  }, 250);
}

function bump() {
  version++;
  const pending = waiters;
  waiters = [];
  for (const resolve of pending) resolve(version);
  scheduleSave();
}

function waitForChange(since, timeoutMs) {
  if (!Number.isFinite(since) || since !== version) return Promise.resolve(version);
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; clearTimeout(t); resolve(v); } };
    const t = setTimeout(() => {
      waiters = waiters.filter((w) => w !== finish);
      finish(version);
    }, timeoutMs);
    waiters.push(finish);
  });
}

function newId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

function addTodo(text) {
  const clean = String(text || '').replace(/\s+/g, ' ').trim().slice(0, 200);
  if (!clean) return null;
  const todo = { id: newId(), text: clean, done: false, createdAt: Date.now() };
  state.todos.push(todo);
  bump();
  return todo;
}

function toggleTodo(id) {
  const todo = state.todos.find((t) => t.id === id);
  if (!todo) return false;
  todo.done = !todo.done;
  todo.doneAt = todo.done ? Date.now() : null;
  bump();
  return true;
}

function deleteTodo(id) {
  const before = state.todos.length;
  state.todos = state.todos.filter((t) => t.id !== id);
  if (state.todos.length === before) return false;
  bump();
  return true;
}

function clearDone() {
  const before = state.todos.length;
  state.todos = state.todos.filter((t) => !t.done);
  if (state.todos.length === before) return false;
  bump();
  return true;
}

function setScene(id) {
  if (!idle.isScene(id) || state.scene === id) return false;
  state.scene = id;
  bump();
  return true;
}

function stopwatchStart() {
  const sw = state.stopwatch;
  if (sw.running) return;
  sw.running = true;
  sw.startedAt = Date.now();
  bump();
}

function stopwatchStop() {
  const sw = state.stopwatch;
  if (!sw.running) return;
  sw.accumulated += Date.now() - sw.startedAt;
  sw.running = false;
  sw.startedAt = null;
  bump();
}

function stopwatchReset() {
  state.stopwatch = { running: false, startedAt: null, accumulated: 0, laps: [] };
  bump();
}

function stopwatchLap() {
  const sw = state.stopwatch;
  const elapsed = sw.accumulated + (sw.running ? Date.now() - sw.startedAt : 0);
  if (elapsed <= 0) return;
  sw.laps.unshift({ at: elapsed, split: elapsed - (sw.laps[0] ? sw.laps[0].at : 0) });
  sw.laps = sw.laps.slice(0, 20);
  bump();
}

load();

module.exports = {
  getState: () => state,
  getVersion: () => version,
  waitForChange,
  addTodo,
  toggleTodo,
  deleteTodo,
  clearDone,
  setScene,
  stopwatchStart,
  stopwatchStop,
  stopwatchReset,
  stopwatchLap
};
