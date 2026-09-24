// Repo-root autolinking config — used when the SDK is installed from git (package root = repo root).
// js/react-native.config.js stays for `file:../print-nats-sdk/js` installs.
module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './js/android',
        packageImportPath: 'import com.magilhub.printnats.rn.PrintNatsPackage;',
        packageInstance: 'new PrintNatsPackage()',
      },
      // no ios key: RN CLI 4 (RN 0.63) rejects `ios: null` and silently drops the whole package
    },
  },
};
