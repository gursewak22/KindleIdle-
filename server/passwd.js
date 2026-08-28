'use strict';

// npm run passwd -- change the username and password.
//
// Reads from stdin rather than argv so neither ends up in shell history. Node
// has no portable way to switch off terminal echo without a dependency, so the
// typing shows; the alternative was a flag on the command line, which is
// worse, because that copy persists.
//
// Lines are pulled from an async iterator rather than rl.question callbacks:
// with input on a pipe every line arrives at once, and a queued question would
// be left waiting for a line that had already gone past.

const readline = require('readline');
const auth = require('./auth');

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: Boolean(process.stdin.isTTY)
});
const lines = rl[Symbol.asyncIterator]();

function stop(message) {
  rl.close();
  console.error(`\n  ${message}\n`);
  process.exit(1);
}

async function ask(label) {
  process.stdout.write(label);
  const { value, done } = await lines.next();
  if (done) stop('Cancelled. Nothing changed.');
  if (!process.stdin.isTTY) process.stdout.write('\n');
  return String(value).trim();
}

(async () => {
  console.log('\n  Set the Kindle Idle account.');
  console.log('  Every signed-in device is signed out when it changes.\n');

  const name = await ask('  Username: ');
  const first = await ask('  Password: ');
  const again = await ask('  Again:    ');
  rl.close();

  if (first !== again) stop('The passwords do not match. Nothing changed.');

  try {
    await auth.setAccount(name, first);
  } catch (err) {
    stop(`${err.message}. Nothing changed.`);
  }

  console.log('\n  Changed. Restart the server for it to take effect.\n');
})();
