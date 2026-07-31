import axiosInstance from './axiosInstance';
import type { ApiResponse, AuthResponse, LoginRequest, UserResponse } from '@/types/core';

export const authApi = {
  login: async (data: LoginRequest): Promise<AuthResponse> => {
    const response = await axiosInstance.post<ApiResponse<AuthResponse>>('/api/v1/auth/login', data);
    return response.data.data;
  },

  refresh: async (refreshToken: string): Promise<AuthResponse> => {
    const response = await axiosInstance.post<ApiResponse<AuthResponse>>('/api/v1/auth/refresh', { refreshToken });
    return response.data.data;
  },

  logout: async (refreshToken: string | null): Promise<void> => {
    await axiosInstance.post('/api/v1/auth/logout', { refreshToken });
  },

  me: async (): Promise<UserResponse> => {
    const response = await axiosInstance.get<ApiResponse<UserResponse>>('/api/v1/auth/me');
    return response.data.data;
  },

  forgotPassword: async (data: { email: string }): Promise<void> => {
    await axiosInstance.post('/api/v1/auth/forgot-password', data);
  },

  verifyResetOtp: async (data: { email: string; otp: string }): Promise<void> => {
    await axiosInstance.post('/api/v1/auth/verify-reset-otp', data);
  },

  resetPassword: async (data: { email: string; otp: string; newPassword: string }): Promise<void> => {
    await axiosInstance.post('/api/v1/auth/reset-password', data);
  },
};
