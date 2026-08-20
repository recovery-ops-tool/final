import axiosInstance from './axiosInstance';
import type { ApiResponse, PagedResponse } from '@/types/core';
import type { CallLogResponse, CallOutcome, CallStartResponse, CompleteCallRequest } from '@/types/domain';

export const callLogApi = {
  start: async (allocationId: string): Promise<CallStartResponse> => {
    const response = await axiosInstance.post<ApiResponse<CallStartResponse>>('/api/v1/call-logs/start', { allocationId });
    return response.data.data;
  },

  uploadRecording: async (callLogId: string, audio: { uri: string; name: string; type: string }): Promise<void> => {
    const formData = new FormData();
    formData.append('file', { uri: audio.uri, name: audio.name, type: audio.type } as unknown as Blob);
    await axiosInstance.post(`/api/v1/call-logs/${callLogId}/recording`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  complete: async (callLogId: string, data: CompleteCallRequest): Promise<CallLogResponse> => {
    const response = await axiosInstance.patch<ApiResponse<CallLogResponse>>(`/api/v1/call-logs/${callLogId}/complete`, data);
    return response.data.data;
  },

  getByAllocation: async (allocationId: string): Promise<CallLogResponse[]> => {
    const response = await axiosInstance.get<ApiResponse<CallLogResponse[]>>(`/api/v1/call-logs/allocation/${allocationId}`);
    return response.data.data;
  },

  list: async (params: { page?: number; size?: number; outcome?: CallOutcome } = {}): Promise<PagedResponse<CallLogResponse>> => {
    const response = await axiosInstance.get<ApiResponse<PagedResponse<CallLogResponse>>>('/api/v1/call-logs', {
      params: { page: params.page ?? 0, size: params.size ?? 30, outcome: params.outcome },
    });
    return response.data.data;
  },
};
