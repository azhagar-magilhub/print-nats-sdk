// NW.js page script (Node 5 + Chromium 50): start or attach to the sidecar, then load the React bundle.
// ES5 only — this runs on Windows XP.
(function () {
  'use strict';
  var path = nw.require('path');
  var launcher = nw.require('./sidecar-launcher');
  var appDir = path.dirname(process.execPath);
  var java = path.join(appDir, 'jre', 'bin', 'javaw.exe'); // javaw: no console window (Node 5 can't hide it)
  var jar = path.join(appDir, 'print-nats-desktop.jar');
  var allUsers = process.env.ALLUSERSPROFILE; // XP: C:\Documents and Settings\All Users; 7+: C:\ProgramData
  var sidecar = null;

  function loadApp() {
    var s = document.createElement('script');
    s.src = 'app/main.js'; // React (react-native-web) bundle built for Chrome 49
    document.body.appendChild(s);
  }

  launcher.attachOrLaunch({
    javaPath: java,
    jarPath: jar,
    dataDir: path.join(process.env.APPDATA || appDir, 'PrintNats'),
    serviceDataDir: allUsers ? path.join(allUsers, 'PrintNats') : null
  }, function (err, sc) {
    if (err) {
      alert('The printing service could not start:\n' + err.message);
    } else {
      sidecar = sc;
      window.__PRINT_NATS__ = { port: sc.port, token: sc.token };
    }
    loadApp();
  });

  var win = nw.Window.get();
  win.on('close', function () {
    if (sidecar) sidecar.stop(); // no-op when attached to the Windows service
    this.close(true);
  });
})();
