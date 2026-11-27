import { Capacitor, CapacitorHttp } from '@capacitor/core';

const DEFAULT_UA =
  'Mozilla/5.0 (Linux; Android 13; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const DEV_PROXY_MAP: Record<string, string> = {
  'https://yanhh3d.ee': '/yan-proxy'
};

function resolveDevProxyUrl(url: string): string {
  if (!import.meta.env.DEV) return url;
  for (const [origin, proxyPrefix] of Object.entries(DEV_PROXY_MAP)) {
    if (url.startsWith(`${origin}/`) || url === origin) {
      return url.replace(origin, proxyPrefix);
    }
  }
  return url;
}

/**
 * Gửi HTTP GET tương thích đồng thời Android Native (CapacitorHttp bỏ qua CORS)
 * và Web/Vite Dev (fetch kèm proxy dev nếu cần).
 */
export async function httpGetText(
  url: string,
  headers: Record<string, string> = {},
  timeoutMs = 8000
): Promise<string> {
  if (Capacitor.isNativePlatform()) {
    const res = await CapacitorHttp.get({
      url,
      headers: {
        'User-Agent': DEFAULT_UA,
        ...headers
      },
      connectTimeout: timeoutMs,
      readTimeout: timeoutMs
    });
    if (res.status < 200 || res.status >= 400) {
      throw new Error(`Native HTTP ${res.status}`);
    }
    return typeof res.data === 'string' ? res.data : JSON.stringify(res.data);
  }

  // Browser fallback (dùng Vite dev proxy cho các site WP khi chạy dev)
  const targetUrl = resolveDevProxyUrl(url);

  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(targetUrl, {
      headers: {
        ...headers
      },
      signal: controller.signal
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } finally {
    window.clearTimeout(timer);
  }
}

export async function httpGetJson<T = any>(
  url: string,
  headers: Record<string, string> = {},
  timeoutMs = 8000
): Promise<{ data: T; headers: Record<string, string> }> {
  if (Capacitor.isNativePlatform()) {
    const res = await CapacitorHttp.get({
      url,
      headers: {
        Accept: 'application/json',
        'User-Agent': DEFAULT_UA,
        ...headers
      },
      connectTimeout: timeoutMs,
      readTimeout: timeoutMs
    });
    if (res.status < 200 || res.status >= 400) {
      throw new Error(`Native HTTP ${res.status}`);
    }
    const parsed = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
    return { data: parsed as T, headers: res.headers || {} };
  }

  const targetUrl = resolveDevProxyUrl(url);

  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(targetUrl, {
      headers: {
        Accept: 'application/json',
        ...headers
      },
      signal: controller.signal
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const outHeaders: Record<string, string> = {};
    res.headers.forEach((v, k) => {
      outHeaders[k.toLowerCase()] = v;
    });
    return { data: (await res.json()) as T, headers: outHeaders };
  } finally {
    window.clearTimeout(timer);
  }
}
