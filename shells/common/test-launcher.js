/* Smoke test (node >= 5): node shells/common/test-launcher.js <java> <print-nats-desktop.jar> */
'use strict';
var launcher = require('./sidecar-launcher');
var os = require('os');
var path = require('path');
var fs = require('fs');

var java = process.argv[2];
var jar = process.argv[3];
var dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pn-shell-'));
var started = Date.now();

var sidecar = launcher.launch({ javaPath: java, jarPath: jar, dataDir: dataDir }, function (err, sc) {
  if (err) {
    console.error('FAIL launch:', err.message);
    process.exit(1);
  }
  console.log('READY after', Date.now() - started, 'ms, port', sc.port);
  launcher.ping(sc.port, sc.token, function (ok) {
    if (!ok) {
      console.error('FAIL: sidecar did not answer with the token');
      sc.stop();
      process.exit(1);
    }
    launcher.ping(sc.port, 'wrong-token', function (okWrong) {
      if (okWrong) {
        console.error('FAIL: wrong token accepted');
        sc.stop();
        process.exit(1);
      }
      launcher.attach(dataDir, function (e2, attached) {
        if (e2 || attached.port !== sc.port) {
          console.error('FAIL attach:', e2 && e2.message);
          sc.stop();
          process.exit(1);
        }
        console.log('PASS: launch, token auth, attach via endpoint.json');
        sc.stop();
        setTimeout(function () { process.exit(0); }, 300);
      });
    });
  });
});
