import axiosInstance from './axiosInstance';
import type { ApiResponse, PagedResponse } from '@/types/core';
import type { VisitLogRequest, VisitLogResponse } from '@/types/domain';

export interface VisitPhoto {
  uri: string;
  name: string;
  type: string;
}

export const visitLogApi = {
  /**
   * POST is multipart: a JSON `data` part plus up to 3 optional image parts.
   * `idempotencyKey` is optional but should always be passed by callers that might
   * retry the same logical submission (a network timeout doesn't tell you whether
   * the server actually received it) -- the backend claims it via VisitLogController's
   * Idempotency-Key header and replays the existing result instead of double-creating.
   * On replay the response's `data` is null (server intentionally omits refetching the
   * original visit), so callers that need the created id should not rely on a replayed
   * response having one.
   */
  create: async (data: VisitLogRequest, photos: VisitPhoto[], idempotencyKey?: string): Promise<VisitLogResponse | null> => {
    const formData = new FormData();
    formData.append('data', {
      string: JSON.stringify(data),
      type: 'application/json',
    } as unknown as Blob);

    photos.slice(0, 3).forEach((photo, index) => {
      formData.append(`image${index + 1}`, {
        uri: photo.uri,
        name: photo.name,
        type: photo.type,
      } as unknown as Blob);
    });

    const response = await axiosInstance.post<ApiResponse<VisitLogResponse | null>>('/api/v1/visit-logs', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
        ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
      },
    });
    return response.data.data;
  },

  getByAllocation: async (allocationId: string): Promise<VisitLogResponse[]> => {
    const response = await axiosInstance.get<ApiResponse<VisitLogResponse[]>>(`/api/v1/visit-logs/allocation/${allocationId}`);
    return response.data.data;
  },

  getLastLocation: async (allocationId: string): Promise<{ lat: number; lng: number } | null> => {
    const response = await axiosInstance.get<ApiResponse<{ lat: number; lng: number } | null>>(
      `/api/v1/visit-logs/allocation/${allocationId}/last-location`,
    );
    return response.data.data;
  },

  listMyVisits: async (params?: { page?: number; size?: number }): Promise<PagedResponse<VisitLogResponse>> => {
    const response = await axiosInstance.get<ApiResponse<PagedResponse<VisitLogResponse>>>('/api/v1/visit-logs', {
      params: {
        page: params?.page ?? 0,
        size: params?.size ?? 100,
        sort: 'visitDate,desc'
      }
    });
    return response.data.data;
  },

  getById: async (id: string): Promise<VisitLogResponse> => {
    const response = await axiosInstance.get<ApiResponse<VisitLogResponse>>(`/api/v1/visit-logs/${id}`);
    return response.data.data;
  },
};
