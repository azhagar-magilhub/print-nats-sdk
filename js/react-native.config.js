module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.magilhub.printnats.rn.PrintNatsPackage;',
        packageInstance: 'new PrintNatsPackage()',
      },
      ios: null,
    },
  },
};
