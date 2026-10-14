export type NativeTransport = {
  request(path: string, method: string, body: string): string;
  requestAsync?(id: string, path: string, method: string, body: string): void;
};

/** Native I/O returns via an event so an unreachable Home Node cannot freeze WebView. */
export function nativeRequest(bridge: NativeTransport, path: string, method: string, body: string,
  target: EventTarget = window, timeoutMs = 20000): Promise<string> {
  if (!bridge.requestAsync) return Promise.resolve(bridge.request(path,method,body));
  const id = crypto.randomUUID();
  return new Promise((resolve,reject) => {
    const cleanup = () => { clearTimeout(timer); target.removeEventListener("home-node-response",listener); };
    const listener = (event: Event) => {
      const detail=(event as CustomEvent<{id:string;response:string}>).detail;
      if(detail?.id!==id) return;
      cleanup(); resolve(detail.response);
    };
    const timer=setTimeout(()=>{cleanup();reject(new Error("Home Node response timeout"));},timeoutMs);
    target.addEventListener("home-node-response",listener);
    try { bridge.requestAsync!(id,path,method,body); } catch(error) { cleanup();reject(error); }
  });
}
