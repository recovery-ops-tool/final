import axios from 'axios';
import * as Sentry from '@sentry/react';
import { toastBus } from '../utils/toastBus';
import { extractApiError } from '../utils/extractApiError';
import { session } from '../utils/session';
import { getDeviceId } from '../utils/deviceId';

/**
 * SYSTEM 13 TASK 13.3.b: correlate a frontend error with the backend request that triggered it.
 * The backend (RequestLoggingFilter) sets X-Request-Id on every response, success or error, and
 * puts the same id in its own logs/audit trail (server/.../config/RequestLoggingFilter.java,
 * MDC key "requestId" -- see SYSTEM 13's own execution record for why that rename mattered).
 * Tagging Sentry's scope with the most recent one is a coarse approximation (a global tag, not a
 * per-request span) -- good enough for "which request was in flight when this broke," which is
 * the actual debugging question, without needing full request-scoped tracing spans.
 */
const tagRequestId = (headers: unknown) => {
  const requestId = (headers as Record<string, string> | undefined)?.['x-request-id'];
  if (requestId) Sentry.setTag('requestId', requestId);
};

declare module 'axios' {
  interface AxiosRequestConfig {
    _retry?: boolean;
    _retryCount?: number;
    _noRetry?: boolean;
    _noToast?: boolean;
  }
  interface InternalAxiosRequestConfig {
    _retry?: boolean;
    _retryCount?: number;
    _noRetry?: boolean;
    _noToast?: boolean;
  }
}

let accessToken: string | null = null;
let refreshToken: string | null = null;
let refreshPromise: Promise<string> | null = null;

// Cross-tab token synchronisation: when one tab refreshes the token, broadcast
// the new access token so sibling tabs can update their in-memory copy instead
// of racing to refresh simultaneously.
const AUTH_CHANNEL = 'recoverpro-auth';
let authChannel: BroadcastChannel | null = null;
try {
  authChannel = new BroadcastChannel(AUTH_CHANNEL);
  authChannel.addEventListener('message', (event) => {
    if (event.data?.type === 'token_refreshed') {
      // Don't adopt the raw token from broadcast — clear in-memory copy so the
      // next request silently re-refreshes via the updated refresh token in storage.
      accessToken = null;
    }
    if (event.data?.type === 'session_expired') {
      accessToken = null;
      refreshToken = null;
      if (window.location.pathname !== '/login') {
        window.location.href = '/login?reason=session_expired';
      }
    }
  });
} catch {
  // BroadcastChannel not supported (e.g., Safari < 15.4)
}

/**
 * Generate a fresh idempotency key (UUID v4-ish) for money-mutating endpoints.
 * Spread into a request config:
 *   axiosInstance.post(url, body, { headers: { 'Idempotency-Key': newIdempotencyKey() } })
 */
export const newIdempotencyKey = (): string => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return (crypto as Crypto).randomUUID();
  }
  return 'idem-' + Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 10);
};

// "Remember this device" controls where auth artefacts live:
//   • remembered  → localStorage  (survives browser close)
//   • not         → sessionStorage (cleared when the browser/tab closes)
//
// The choice is set explicitly at login via setRememberDevice(boolean). On
// page reload, we infer it from where tokens currently are (sessionStorage
// wins because a session-store token must have come from a recent
// "don't remember" login).

const REFRESH_KEY = 'recoverpro_refresh_token';
const USER_KEY    = 'recoverpro_user';
const ETAG_KEY    = 'recoverpro_user_etag';

let useSession = sessionStorage.getItem(REFRESH_KEY) != null;

export const setRememberDevice = (remember: boolean) => {
  useSession = !remember;
};

const authStore = (): Storage => (useSession ? sessionStorage : localStorage);
const otherStore = (): Storage => (useSession ? localStorage : sessionStorage);

const writeAuthItem = (key: string, value: string | null) => {
  if (value == null) {
    localStorage.removeItem(key);
    sessionStorage.removeItem(key);
    return;
  }
  authStore().setItem(key, value);
  otherStore().removeItem(key);
};

const readAuthItem = (key: string): string | null =>
  sessionStorage.getItem(key) ?? localStorage.getItem(key);

export const setAccessToken = (token: string | null) => {
  accessToken = token;
};

export const setRefreshToken = (token: string | null) => {
  refreshToken = token;
  writeAuthItem(REFRESH_KEY, token);
};

export const getAccessToken = () => accessToken;

export const getRefreshToken = () => {
  if (refreshToken) return refreshToken;
  return readAuthItem(REFRESH_KEY);
};

// User-cache helpers (use these instead of localStorage.*('recoverpro_user'))
export const setUserCache = (user: unknown | null) => {
  writeAuthItem(USER_KEY, user == null ? null : JSON.stringify(user));
};
export const getUserCache = (): string | null => readAuthItem(USER_KEY);

export const setUserEtag = (etag: string | null) => writeAuthItem(ETAG_KEY, etag);
export const getUserEtag = (): string | null => readAuthItem(ETAG_KEY);

/** Shared refresh execution — deduplicates concurrent calls via refreshPromise. */
function doRefresh(rawRefreshToken: string): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = axios
      .post('/api/v1/auth/refresh', { refreshToken: rawRefreshToken }, {
        timeout: 15_000,
        headers: { 'X-Device-Id': getDeviceId() },
      })
      .then(({ data }) => {
        const authResponse = data.data || data;
        setAccessToken(authResponse.accessToken);
        if (authResponse.refreshToken) setRefreshToken(authResponse.refreshToken);
        if (authResponse.user) setUserCache(authResponse.user);
        if (authResponse.expiresIn) {
          session.setExpiresAt(new Date(Date.now() + authResponse.expiresIn * 1000).toISOString());
        }
        authChannel?.postMessage({ type: 'token_refreshed' });
        return authResponse.accessToken as string;
      })
      .finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

/**
 * Proactive token refresh that shares the same refreshPromise deduplicator
 * as the 401 interceptor. Safe to call concurrently — if a refresh is already
 * in flight, this joins it instead of firing a second request with the same
 * refresh token (which would cause the server to revoke the session family).
 */
export const proactiveRefresh = async (): Promise<void> => {
  const rt = getRefreshToken();
  if (!rt) return;
  await doRefresh(rt);
};

export const clearAllAuthStorage = () => {
  refreshToken = null;
  accessToken = null;
  [REFRESH_KEY, USER_KEY, ETAG_KEY].forEach((k) => {
    localStorage.removeItem(k);
    sessionStorage.removeItem(k);
  });
};

const axiosInstance = axios.create({
  baseURL: '',
  timeout: 30_000,
  headers: {
    'Content-Type': 'application/json',
  },
});

axiosInstance.interceptors.request.use(
  (config) => {
    if (accessToken) {
      config.headers.Authorization = `Bearer ${accessToken}`;
    }
    config.headers['X-Device-Id'] = getDeviceId();
    return config;
  },
  (error) => Promise.reject(error)
);

const RETRYABLE_STATUS = new Set([429, 502, 503, 504]);

axiosInstance.interceptors.response.use(
  (response) => {
    tagRequestId(response.headers);
    return response;
  },
  async (error) => {
    // Aborted requests are never retried or redirected.
    if (axios.isCancel(error)) return Promise.reject(error);

    tagRequestId(error.response?.headers);

    const config = error.config;
    const status: number | undefined = error.response?.status;

    // ── 401: token refresh then replay ────────────────────────────────────
    if (status === 401 && config && !config._retry) {
      config._retry = true;
      const storedRefreshToken = getRefreshToken();

      if (!storedRefreshToken) {
        clearAllAuthStorage();
        authChannel?.postMessage({ type: 'session_expired' });
        if (window.location.pathname !== '/login') {
          window.location.href = '/login?reason=session_expired';
        }
        return Promise.reject(error);
      }

      try {
        const newToken = await doRefresh(storedRefreshToken);
        config.headers.Authorization = `Bearer ${newToken}`;
        return axiosInstance(config);
      } catch (refreshError) {
        clearAllAuthStorage();
        authChannel?.postMessage({ type: 'session_expired' });
        if (window.location.pathname !== '/login') {
          window.location.href = '/login?reason=session_expired';
        }
        return Promise.reject(refreshError);
      }
    }

    // ── 429 / 5xx / network error: exponential backoff retry ──────────────
    if (config && !config._noRetry) {
      const retries: number = config._retryCount ?? 0;
      const isNetworkError = !error.response;
      const isRetryable = isNetworkError || (status !== undefined && RETRYABLE_STATUS.has(status));
      const maxRetries = status === 429 ? 3 : 2;

      if (isRetryable && retries < maxRetries) {
        config._retryCount = retries + 1;
        let delay: number;
        if (status === 429) {
          const retryAfter = Number(error.response?.headers?.['retry-after'] ?? 0);
          delay = retryAfter > 0 ? retryAfter * 1_000 : Math.min(1_000 * 2 ** retries, 30_000);
        } else {
          delay = Math.min(500 * 2 ** retries, 10_000);
        }
        await new Promise(r => setTimeout(r, delay));
        return axiosInstance(config);
      }
    }

    // ── Auto-toast for final errors (after retries exhausted) ─────────────
    const isCanceled  = axios.isCancel(error);
    const isUnhandled = !config?._noToast;
    const finalStatus: number | undefined = error.response?.status;
    // Skip: canceled, 401 (handled via redirect), 422 (form-level validation shown inline)
    const skipToast = isCanceled || finalStatus === 401 || finalStatus === 422;

    if (!skipToast && isUnhandled) {
      const msg = extractApiError(error);
      if (msg) toastBus.error('Request failed', msg);
    }

    return Promise.reject(error);
  }
);

export default axiosInstance;
