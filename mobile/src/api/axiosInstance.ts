import axios from 'axios';
import type { ApiResponse, AuthResponse } from '@/types/core';
import { API_BASE_URL } from './apiConfig';
import {
  getAccessToken, setAccessToken, loadRefreshToken, saveRefreshToken,
  saveUserCache, clearAllAuthStorage,
} from './tokenStore';

declare module 'axios' {
  interface InternalAxiosRequestConfig {
    _retry?: boolean;
  }
}

/** Fresh idempotency key for money-mutating endpoints (PTPs, payment intents). */
export const newIdempotencyKey = (): string =>
  'idem-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);

let refreshPromise: Promise<string> | null = null;
let onSessionExpired: (() => void) | null = null;

/** AuthContext registers a callback so a dead refresh token routes back to login. */
export const setOnSessionExpired = (fn: (() => void) | null) => {
  onSessionExpired = fn;
};

function doRefresh(rawRefreshToken: string): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = axios
      .post<ApiResponse<AuthResponse>>(
        `${API_BASE_URL}/api/v1/auth/refresh`,
        { refreshToken: rawRefreshToken },
        { timeout: 15_000 },
      )
      .then(async ({ data }) => {
        const auth = data.data;
        setAccessToken(auth.accessToken);
        if (auth.refreshToken) await saveRefreshToken(auth.refreshToken);
        if (auth.user) await saveUserCache(auth.user);
        return auth.accessToken;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

const axiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 180_000,
  headers: { 'Content-Type': 'application/json' },
});

axiosInstance.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

axiosInstance.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (axios.isCancel(error)) return Promise.reject(error);

    const config = error.config;
    const status: number | undefined = error.response?.status;

    if (status === 401 && config && !config._retry) {
      config._retry = true;
      const storedRefreshToken = await loadRefreshToken();

      if (!storedRefreshToken) {
        await clearAllAuthStorage();
        onSessionExpired?.();
        return Promise.reject(error);
      }

      try {
        const newToken = await doRefresh(storedRefreshToken);
        config.headers.Authorization = `Bearer ${newToken}`;
        return axiosInstance(config);
      } catch (refreshError) {
        await clearAllAuthStorage();
        onSessionExpired?.();
        return Promise.reject(refreshError);
      }
    }

    return Promise.reject(error);
  },
);

export default axiosInstance;
