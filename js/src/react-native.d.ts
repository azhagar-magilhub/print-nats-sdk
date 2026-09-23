// Minimal ambient types so `npm run typecheck` works without installing react-native.
declare module 'react-native' {
  export const NativeModules: Record<string, any>;
  export class NativeEventEmitter {
    constructor(nativeModule?: any);
    addListener(event: string, cb: (payload: any) => void): { remove(): void };
  }
}
