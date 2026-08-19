import axiosInstance from './axiosInstance';
import type { ApiResponse, UserResponse } from '../types';

/** Mirrors server OrganizationSummaryResponse exactly — no admin first/last name here
 *  (the backend keeps the list endpoint cheap); fetch full admin details via
 *  platformApi.getOrganizationUsers(id) when a single org's admin needs editing. */
export interface OrganizationSummary {
  id: string;
  name: string;
  code: string;
  orgType?: string;
  isActive: boolean;
  createdAt: string;
  userCount: number;
  orgAdminEmail?: string;
}

export interface CreateOrganizationRequest {
  name: string;
  code: string;
  adminEmail: string;
  adminFirstName: string;
  adminLastName: string;
}

export interface UpdateOrganizationRequest {
  name: string;
  code: string;
}

export interface UpdateOrgAdminRequest {
  firstName: string;
  lastName: string;
  email: string;
}

export const platformApi = {
  listOrganizations: async (): Promise<OrganizationSummary[]> => {
    const r = await axiosInstance.get<ApiResponse<OrganizationSummary[]>>(
      '/api/v1/platform/organizations',
    );
    return r.data.data;
  },

  getOrganization: async (id: string): Promise<OrganizationSummary> => {
    const r = await axiosInstance.get<ApiResponse<OrganizationSummary>>(
      `/api/v1/platform/organizations/${id}`,
    );
    return r.data.data;
  },

  getOrganizationUsers: async (id: string): Promise<UserResponse[]> => {
    const r = await axiosInstance.get<ApiResponse<UserResponse[]>>(
      `/api/v1/platform/organizations/${id}/users`,
    );
    return r.data.data;
  },

  createOrganization: async (data: CreateOrganizationRequest): Promise<OrganizationSummary> => {
    const r = await axiosInstance.post<ApiResponse<OrganizationSummary>>(
      '/api/v1/platform/organizations',
      data,
    );
    return r.data.data;
  },

  updateOrganization: async (id: string, data: UpdateOrganizationRequest): Promise<OrganizationSummary> => {
    const r = await axiosInstance.patch<ApiResponse<OrganizationSummary>>(
      `/api/v1/platform/organizations/${id}`,
      data,
    );
    return r.data.data;
  },

  updateOrgAdmin: async (id: string, data: UpdateOrgAdminRequest): Promise<OrganizationSummary> => {
    const r = await axiosInstance.patch<ApiResponse<OrganizationSummary>>(
      `/api/v1/platform/organizations/${id}/admin`,
      data,
    );
    return r.data.data;
  },

  deleteOrganization: async (id: string): Promise<void> => {
    await axiosInstance.delete(`/api/v1/platform/organizations/${id}`);
  },

  setActive: async (id: string, active: boolean): Promise<OrganizationSummary> => {
    const r = await axiosInstance.patch<ApiResponse<OrganizationSummary>>(
      `/api/v1/platform/organizations/${id}/active`,
      null,
      { params: { active } },
    );
    return r.data.data;
  },

  listAdminUsers: async (): Promise<UserResponse[]> => {
    const r = await axiosInstance.get<ApiResponse<UserResponse[]>>('/api/v1/platform/users');
    return r.data.data;
  },

  createAdminUser: async (data: {
    email: string;
    firstName: string;
    lastName: string;
    role: string;
    organizationId?: string;
  }): Promise<UserResponse> => {
    const r = await axiosInstance.post<ApiResponse<UserResponse>>('/api/v1/platform/users', data);
    return r.data.data;
  },

  getStats: async (): Promise<PlatformStats> => {
    const r = await axiosInstance.get<ApiResponse<PlatformStats>>('/api/v1/platform/stats');
    return r.data.data;
  },

  getAnalytics: async (months = 6): Promise<PlatformAnalytics> => {
    const r = await axiosInstance.get<ApiResponse<PlatformAnalytics>>(
      '/api/v1/platform/analytics',
      { params: { months } },
    );
    return r.data.data;
  },

  // Platform-wide billing console, backed by PlatformSubscriptionController.
  // Distinct from subscriptionApi.ts, which is self-service for the caller's own org.
  listSubscriptions: async (): Promise<PlatformSubRow[]> => {
    const r = await axiosInstance.get<ApiResponse<PlatformSubRow[]>>(
      '/api/v1/platform/subscriptions',
    );
    return r.data.data;
  },

  /**
   * @param basis `contracted` (default) = active subs × plan amount, an MRR
   *   snapshot, in RUPEES. `collected` = invoices Stripe actually settled, in
   *   PAISE. They diverge whenever a charge fails, prorates or is refunded.
   *   Mind the units — see RevenueTrendPoint.revenue.
   */
  getRevenueTrend: async (
    granularity = 'monthly',
    periods = 0,
    basis: RevenueBasis = 'contracted',
  ): Promise<RevenueTrendPoint[]> => {
    const r = await axiosInstance.get<ApiResponse<RevenueTrendPoint[]>>(
      '/api/v1/platform/subscriptions/revenue-trend',
      { params: { granularity, periods, basis } },
    );
    return r.data.data;
  },

  listInvoices: async (orgId: string): Promise<InvoiceRow[]> => {
    const r = await axiosInstance.get<ApiResponse<InvoiceRow[]>>(
      `/api/v1/platform/subscriptions/${orgId}/invoices`,
    );
    return r.data.data;
  },

  /**
   * Sets the plan an org pays for. Rejected for Stripe-billed orgs, where Stripe
   * owns the plan and would overwrite this on the next subscription webhook —
   * use grantComp to give access without charging them.
   */
  changePlan: async (orgId: string, plan: string): Promise<void> => {
    await axiosInstance.put(`/api/v1/platform/subscriptions/${orgId}/plan`, { plan });
  },

  /**
   * Grants plan access the org has not paid for. Survives Stripe webhooks,
   * leaves revenue figures untouched, and replaces any existing grant.
   *
   * @param until ISO-8601 instant, or omit for an open-ended grant
   */
  grantComp: async (
    orgId: string,
    plan: string,
    reason: string,
    until?: string | null,
  ): Promise<void> => {
    await axiosInstance.put(`/api/v1/platform/subscriptions/${orgId}/comp`, {
      plan, reason, ...(until ? { until } : {}),
    });
  },

  /** Ends a grant; the org drops back to what it actually pays for. */
  revokeComp: async (orgId: string): Promise<void> => {
    await axiosInstance.delete(`/api/v1/platform/subscriptions/${orgId}/comp`);
  },

  /**
   * One-shot import of historical Stripe invoices into the local mirror.
   * Idempotent — keyed on the Stripe invoice id, so re-running refreshes rows
   * rather than duplicating them. Until this has run at least once, every
   * collected-revenue figure legitimately reads zero.
   *
   * @returns how many invoices were written or refreshed
   */
  backfillInvoices: async (): Promise<number> => {
    const r = await axiosInstance.post<ApiResponse<{ imported: number }>>(
      '/api/v1/platform/subscriptions/backfill-invoices',
    );
    return r.data.data.imported;
  },
};

export interface PlatformSubRow {
  orgId: string;
  orgName: string;
  orgCode: string;
  status: 'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'CANCELLED' | 'INACTIVE';
  plan: 'NONE' | 'STARTER' | 'GROWTH' | 'ENTERPRISE';
  stripeCustomerId: string | null;
  stripeSubscriptionId: string | null;
  trialEndsAt: string | null;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
  createdAt: string;
  trialDaysLeft: number;
  /**
   * PAISE this org has actually paid us, over settled invoices only.
   * Collected, not contracted — an org whose last charges failed shows its real
   * contribution here. Divide by 100 to display.
   */
  lifetimeRevenue: number;

  /** Plan granted without payment, or null. Never counts as revenue. */
  compedPlan: 'STARTER' | 'GROWTH' | 'ENTERPRISE' | null;
  /** Null alongside a compedPlan means the grant is open-ended. */
  compedUntil: string | null;
  compedReason: string | null;
  compedAt: string | null;
  /**
   * What the org can actually use right now — the live comp if there is one,
   * otherwise `plan`. Gate on this; `plan` is only what they pay for, and the
   * two differ whenever a grant is active.
   */
  effectivePlan: 'NONE' | 'STARTER' | 'GROWTH' | 'ENTERPRISE';
}

/** Which question the revenue trend answers. See platformApi.getRevenueTrend. */
export type RevenueBasis = 'contracted' | 'collected';

export interface RevenueTrendPoint {
  /** Display label already formatted by the server: "Jan 2026", "07 Feb", "2026". */
  month: string;
  /**
   * UNITS DEPEND ON THE `basis` YOU REQUESTED:
   *   contracted → RUPEES
   *   collected  → PAISE
   * Formatting paise as rupees renders every figure 100× too large, so convert
   * at the point where you know which basis you asked for.
   */
  revenue: number;
  count: number;
}

/** Monthly point from the platform analytics endpoint (mirrors server TrendPoint). */
export interface PlatformTrendPoint {
  year: number;
  month: number;
  label: string;
  totalAmount: number;
  totalCount: number;
}

export interface PlatformAnalytics {
  mrr: number;
  arr: number;
  mrrGrowthRate: number;
  payingOrgs: number;
  trialOrgs: number;
  trialsEndingSoon: number;
  planCounts: { none: number; starter: number; growth: number; enterprise: number };
  statusCounts: { trial: number; active: number; pastDue: number; cancelled: number; inactive: number };
  revenueTrend: PlatformTrendPoint[];
  orgGrowthTrend: PlatformTrendPoint[];
  topOrgsByUsers: { name: string; value: number }[];
  userGrowthTrend: PlatformTrendPoint[];
}

export interface InvoiceRow {
  id: string;
  number: string;
  status: string;
  amountPaid: number;
  currency: string;
  date: string;
  pdfUrl: string | null;
  hostedUrl: string | null;
}

export interface PlatformStats {
  totalOrgs: number;
  activeOrgs: number;
  inactiveOrgs: number;
  newOrgsThisMonth: number;
  totalUsers: number;
  roleBreakdown: {
    orgAdmin: number;
    manager: number;
    tl: number;
    fo: number;
    caller: number;
    tracer: number;
  };
  totalAllocations: number;
  totalUploads: number;
  totalRowsProcessed: number;
  uploadsLast7Days: number;
  pendingUserRequests: number;
  staleOrgs: number;
}
