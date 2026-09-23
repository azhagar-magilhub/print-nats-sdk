; Inno Setup 5.6.x script (last Inno version that builds XP-compatible installers).
; Layout expected next to this file:  nwjs\ (NW.js 0.14.7 win-ia32 files)  jre\ (Java 8 32-bit)
;   print-nats-desktop.jar  index.html  bootstrap.js  sidecar-launcher.js  package.json  app\  winsw\ (optional)
[Setup]
AppName=MerchantPOS
AppVersion=0.1.0
DefaultDirName={pf}\MerchantPOS
DefaultGroupName=MerchantPOS
OutputBaseFilename=MerchantPOS-XP-Setup
MinVersion=5.1
ArchitecturesAllowed=x86 x64
PrivilegesRequired=admin

[Files]
Source: "nwjs\*"; DestDir: "{app}"; Flags: recursesubdirs
Source: "jre\*"; DestDir: "{app}\jre"; Flags: recursesubdirs
Source: "print-nats-desktop.jar"; DestDir: "{app}"
Source: "index.html"; DestDir: "{app}"
Source: "bootstrap.js"; DestDir: "{app}"
Source: "sidecar-launcher.js"; DestDir: "{app}"
Source: "package.json"; DestDir: "{app}"
Source: "app\*"; DestDir: "{app}\app"; Flags: recursesubdirs
Source: "winsw\*"; DestDir: "{app}\service"; Flags: recursesubdirs skipifsourcedoesntexist

[Icons]
Name: "{group}\MerchantPOS"; Filename: "{app}\nw.exe"
Name: "{commondesktop}\MerchantPOS"; Filename: "{app}\nw.exe"

[Tasks]
Name: "printservice"; Description: "Keep printing when MerchantPOS is closed (Windows service)"; Flags: unchecked

[Run]
Filename: "{app}\service\install-service.cmd"; Parameters: """{app}"""; Tasks: printservice; Flags: runhidden waituntilterminated

[UninstallRun]
Filename: "{app}\service\uninstall-service.cmd"; Flags: runhidden skipifdoesntexist
