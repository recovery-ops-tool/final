import axiosInstance from './axiosInstance';
import type { ApiResponse } from '@/types/core';

export interface StartSessionRequest {
  agentId: string;
  agentFirstName: string;
  allocationId?: string;
}

export interface SessionResponse {
  sessionId: string;
  agentId: string;
  agentFirstName: string;
  active: boolean;
  totalMessages: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChatRequest {
  sessionId: string;
  message: string;
}

export interface ChatResponse {
  messageId: string;
  sessionId: string;
  reply: string;
  blocked: boolean;
  blockReason?: string;
  inputSafetyDecision?: string;
  outputSafetyDecision?: string;
  latencyMs?: number;
  timestamp: string;
  modelName?: string;
  confirmationRequired: boolean;
  pendingActionId?: string;
  pendingActionSummary?: string;
  pendingToolName?: string;
}

export interface ChatMessageResponse {
  id: string;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  wasBlocked: boolean;
  createdAt: string;
}

export interface ConfirmActionRequest {
  actionId: string;
  confirmed: boolean;
}

export interface ConfirmVisitActionRequest {
  actionId: string;
  confirmed: boolean;
  latitude?: number;
  longitude?: number;
  gpsAccuracy?: number;
  mockLocationDetected?: boolean;
}

export interface VisitPhoto {
  uri: string;
  name: string;
  type: string;
}

export const lucienApi = {
  startSession: async (data: StartSessionRequest): Promise<SessionResponse> => {
    const response = await axiosInstance.post<ApiResponse<SessionResponse>>('/api/v1/lucien/sessions', data);
    return response.data.data;
  },

  sendMessage: async (data: ChatRequest): Promise<ChatResponse> => {
    const response = await axiosInstance.post<ApiResponse<ChatResponse>>('/api/v1/lucien/chat', data);
    return response.data.data;
  },

  confirmAction: async (sessionId: string, data: ConfirmActionRequest): Promise<ChatResponse> => {
    const response = await axiosInstance.post<ApiResponse<ChatResponse>>(
      `/api/v1/lucien/sessions/${sessionId}/confirm`,
      data,
    );
    return response.data.data;
  },

  confirmVisitAction: async (
    sessionId: string,
    data: ConfirmVisitActionRequest,
    image1: VisitPhoto,
    image2?: VisitPhoto,
  ): Promise<ChatResponse> => {
    const formData = new FormData();
    formData.append('data', {
      string: JSON.stringify(data),
      type: 'application/json',
    } as unknown as Blob);

    formData.append('image1', {
      uri: image1.uri,
      name: image1.name,
      type: image1.type,
    } as unknown as Blob);

    if (image2) {
      formData.append('image2', {
        uri: image2.uri,
        name: image2.name,
        type: image2.type,
      } as unknown as Blob);
    }

    const response = await axiosInstance.post<ApiResponse<ChatResponse>>(
      `/api/v1/lucien/sessions/${sessionId}/confirm-visit`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      },
    );
    return response.data.data;
  },

  getSessionHistory: async (sessionId: string): Promise<ChatMessageResponse[]> => {
    const response = await axiosInstance.get<ApiResponse<ChatMessageResponse[]>>(`/api/v1/lucien/sessions/${sessionId}/history`);
    return response.data.data;
  },

  closeSession: async (sessionId: string): Promise<void> => {
    await axiosInstance.post(`/api/v1/lucien/sessions/${sessionId}/close`);
  },
};
