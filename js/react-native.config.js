module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.magilhub.printnats.rn.PrintNatsPackage;',
        packageInstance: 'new PrintNatsPackage()',
      },
      // no ios key: RN CLI 4 (RN 0.63) rejects `ios: null` and silently drops the whole package
    },
  },
};
