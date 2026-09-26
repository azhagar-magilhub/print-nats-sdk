/*
 * Starts (or attaches to) the print-nats Java sidecar and hands {port, token} to the UI.
 * Shared by the Electron shell (Windows 10/11) and the NW.js 0.14.7 shell (Windows XP, Node 5) — so this file is
 * deliberately ES5-style: var/function, callbacks, no async/await, no destructuring, no default parameters.
 *
 *   launch({ javaPath, jarPath, dataDir, token? }, function (err, sidecar) { sidecar.port; sidecar.token; sidecar.stop(); })
 *   attach(dataDir, function (err, sidecar) { ... })   // sidecar already running as a Windows service
 */
'use strict';

var childProcess = require('child_process');
var crypto = require('crypto');
var fs = require('fs');
var http = require('http');
var path = require('path');

var READY_TIMEOUT_MS = 60000;
var MAX_RESTART_DELAY_MS = 30000;

function randomToken() {
  return crypto.randomBytes(24).toString('hex');
}

/** POST /v1/connected with the token — true when the sidecar answers 200. */
function ping(port, token, cb) {
  var req = http.request({
    host: '127.0.0.1', port: port, path: '/v1/connected', method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
  }, function (res) {
    res.resume();
    cb(res.statusCode === 200);
  });
  req.on('error', function () { cb(false); });
  req.setTimeout(3000, function () { req.abort(); });
  req.end('{}');
}

/**
 * Spawn `java -jar print-nats-desktop.jar`, wait for "READY port=N", restart it if it dies (backoff 1 s → 30 s).
 * The callback fires once, on the first READY (or on failure to start).
 */
function launch(opts, cb) {
  var token = opts.token || randomToken();
  var args = ['-jar', opts.jarPath, '--port', String(opts.port || 0), '--token', token];
  if (opts.dataDir) args.push('--data-dir', opts.dataDir);
  var child = null;
  var stopped = false;
  var delay = 1000;
  var reported = false;
  var sidecar = {
    port: 0,
    token: token,
    stop: function () {
      stopped = true;
      if (!child) return;
      if (process.platform === 'win32' && child.pid) {
        // child.kill() ends only java.exe on Windows — the nats-server.exe it started would be orphaned and keep the
        // app's install folder locked (installer / uninstaller: "cannot be closed"). End the whole tree.
        try {
          childProcess.execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], {windowsHide: true, stdio: 'ignore'});
        } catch (e) {
          child.kill();
        }
      } else {
        child.kill();
      }
    },
  };

  function start() {
    var buffered = '';
    child = childProcess.spawn(opts.javaPath, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    var timer = setTimeout(function () {
      if (!sidecar.port && !reported) {
        reported = true;
        cb(new Error('print-nats sidecar did not report READY within ' + READY_TIMEOUT_MS / 1000 + ' s'));
      }
    }, READY_TIMEOUT_MS);
    child.stdout.on('data', function (chunk) {
      buffered += chunk.toString();
      var m = /READY port=(\d+)/.exec(buffered);
      if (m) {
        clearTimeout(timer);
        sidecar.port = parseInt(m[1], 10);
        delay = 1000;
        if (!reported) {
          reported = true;
          cb(null, sidecar);
        }
        buffered = '';
      }
    });
    child.stderr.on('data', function (chunk) {
      if (opts.onLog) opts.onLog(chunk.toString());
    });
    child.on('error', function (e) {
      clearTimeout(timer);
      if (!reported) {
        reported = true;
        cb(e);
      }
    });
    child.on('exit', function (code) {
      clearTimeout(timer);
      if (stopped) return;
      if (opts.onLog) opts.onLog('print-nats sidecar exited (' + code + '), restarting in ' + delay + ' ms');
      setTimeout(start, delay);
      delay = Math.min(delay * 2, MAX_RESTART_DELAY_MS);
    });
  }

  start();
  return sidecar;
}

/** Service mode: read <dataDir>/endpoint.json written by the sidecar and check it answers. */
function attach(dataDir, cb) {
  fs.readFile(path.join(dataDir, 'endpoint.json'), 'utf8', function (err, text) {
    if (err) return cb(err);
    var ep;
    try {
      ep = JSON.parse(text);
    } catch (e) {
      return cb(e);
    }
    ping(ep.port, ep.token, function (ok) {
      if (!ok) return cb(new Error('print-nats service not answering on port ' + ep.port));
      cb(null, { port: ep.port, token: ep.token, stop: function () {} });
    });
  });
}

/** Prefer an already-running Windows service; otherwise launch our own sidecar. */
function attachOrLaunch(opts, cb) {
  if (!opts.serviceDataDir) return launch(opts, cb);
  attach(opts.serviceDataDir, function (err, sidecar) {
    if (!err) return cb(null, sidecar);
    launch(opts, cb);
  });
}

module.exports = { launch: launch, attach: attach, attachOrLaunch: attachOrLaunch, ping: ping, randomToken: randomToken };
