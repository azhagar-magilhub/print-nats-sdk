// Signed Windows build: the "build" config from package.json plus Azure Trusted Signing, so
// SmartScreen shows the verified publisher instead of "Windows protected your PC".
// Used by `npm run dist:signed`. Setup and the variables below: README.md, "Code signing" (full guide: maghilOrder desktop/SIGNING.md).
//
// Azure Trusted Signing runs Microsoft's TrustedSigning PowerShell module, so this must run on Windows.
// The account values are not secrets but are read from the environment, so nothing Azure-specific is
// committed; the Entra ID app login (AZURE_TENANT_ID / AZURE_CLIENT_ID / AZURE_CLIENT_SECRET) is a secret
// and must only ever come from the environment.
'use strict';
const base = require('./package.json').build;

if (process.platform !== 'win32') {
  throw new Error('Signed build: Azure Trusted Signing only runs on Windows. Use `npm run dist` for an unsigned build.');
}
const required = ['AZURE_TS_ENDPOINT', 'AZURE_TS_ACCOUNT', 'AZURE_TS_PROFILE', 'AZURE_TS_PUBLISHER', 'AZURE_TENANT_ID', 'AZURE_CLIENT_ID'];
const missing = required.filter(name => !process.env[name]);
if (!process.env.AZURE_CLIENT_SECRET && !process.env.AZURE_CLIENT_CERTIFICATE_PATH) {
  missing.push('AZURE_CLIENT_SECRET (or AZURE_CLIENT_CERTIFICATE_PATH)');
}
if (missing.length > 0) {
  throw new Error(`Signed build: set ${missing.join(', ')} (see README.md, "Code signing").`);
}

module.exports = {
  ...base,
  win: {
    ...base.win,
    azureSignOptions: {
      // e.g. https://eus.codesigning.azure.net/ — the region the Trusted Signing account was created in
      endpoint: process.env.AZURE_TS_ENDPOINT,
      codeSigningAccountName: process.env.AZURE_TS_ACCOUNT,
      certificateProfileName: process.env.AZURE_TS_PROFILE,
      // Must match the certificate subject CN (the validated organisation name), e.g. "Magilhub Private Limited"
      publisherName: process.env.AZURE_TS_PUBLISHER,
    },
  },
};
