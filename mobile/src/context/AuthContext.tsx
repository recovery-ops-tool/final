import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authApi } from '@/api/authApi';
import { setOnSessionExpired } from '@/api/axiosInstance';
import {
  setAccessToken, loadRefreshToken, saveRefreshToken,
  loadUserCache, saveUserCache, clearAllAuthStorage,
} from '@/api/tokenStore';
import type { Role, UserResponse } from '@/types/core';

interface LoginResult {
  mfaRequired: boolean;
}

interface AuthContextValue {
  user: UserResponse | null;
  isLoading: boolean;
  role: Role | null;
  login: (email: string, password: string, totpCode?: string) => Promise<LoginResult>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    setOnSessionExpired(() => {
      setUser(null);
    });

    (async () => {
      const [refreshToken, cachedUser] = await Promise.all([
        loadRefreshToken(),
        loadUserCache<UserResponse>(),
      ]);

      if (!refreshToken) {
        setIsLoading(false);
        return;
      }

      if (cachedUser) setUser(cachedUser);

      try {
        const auth = await authApi.refresh(refreshToken);
        setAccessToken(auth.accessToken);
        await saveRefreshToken(auth.refreshToken);
        await saveUserCache(auth.user);
        setUser(auth.user);
      } catch {
        await clearAllAuthStorage();
        setUser(null);
      } finally {
        setIsLoading(false);
      }
    })();

    return () => setOnSessionExpired(null);
  }, []);

  const login = async (email: string, password: string, totpCode?: string): Promise<LoginResult> => {
    const auth = await authApi.login({ email, password, totpCode });

    if (auth.mfaRequired) {
      return { mfaRequired: true };
    }

    setAccessToken(auth.accessToken);
    await saveRefreshToken(auth.refreshToken);
    await saveUserCache(auth.user);
    
    // Show splash loading transition
    setIsLoading(true);
    await new Promise(resolve => setTimeout(resolve, 1500));
    
    setUser(auth.user);
    setIsLoading(false);
    return { mfaRequired: false };
  };

  const logout = async () => {
    const refreshToken = await loadRefreshToken();
    try {
      await authApi.logout(refreshToken);
    } catch {
      // Best-effort server-side revoke — local session is cleared regardless.
    }
    await clearAllAuthStorage();
    setUser(null);
  };

  // RoleResponse.name arrives as "ROLE_FO" over the wire — normalize to the bare Role union.
  const role = (user?.roles?.[0]?.name?.replace(/^ROLE_/, '') as Role) ?? null;

  const value = useMemo(
    () => ({ user, isLoading, role, login, logout }),
    [user, isLoading, role],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
