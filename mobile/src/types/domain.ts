// Domain DTOs for the Field Officer app. Field names/types are ported
// verbatim from the backend's actual DTO classes (server/src/main/java/com/
// recoverpro/server/dto/{request,response}) — confirmed against the
// @RestController + @PreAuthorize annotations, not just the (sometimes
// stale) web frontend types. Only endpoints FO can actually reach are used.

import type { AllocationStatus, CollectionStatus, PaymentMode, PtpStatus } from './core';

// ── Allocations (cases/loans) ──────────────────────────────────────────────

export type Disp = 'PAID' | 'RTP' | 'NC_SKIP' | 'PTP' | 'FOLLOW_UP';

export interface AllocationResponse {
  id: string;
  fileUploadId: string;
  organizationId: string;
  loanNumber: string;
  borrowerName: string;
  status: AllocationStatus;
  latestDisposition?: Disp;
  assignedToUserId?: string;
  assignedAt?: string;
  dynamicData: Record<string, unknown>;
  rowNumber?: number;
  npaFlagged?: boolean;
  totalDue?: number;
  outstandingAmount?: number;
  borrowerId?: string;
  createdAt: string;
  updatedAt?: string;
  agentName?: string;
  emi?: number;
}

export interface AllocationFilterParams {
  status?: AllocationStatus;
  assignedToUserId?: string;
  searchTerm?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: string;
}

// ── Case activity timeline ─────────────────────────────────────────────────

export type CaseEventType =
  | 'ALLOCATION_CREATED' | 'ALLOCATION_STATUS_CHANGED' | 'ALLOCATION_DISPOSITION_CHANGED' | 'ALLOCATION_CLOSED'
  | 'NPA_FLAG_RAISED' | 'COOLING_OFF_STARTED'
  | 'ASSIGNMENT_CREATED' | 'ASSIGNMENT_REASSIGNED' | 'ASSIGNMENT_CANCELLED' | 'ASSIGNMENT_COMPLETED'
  | 'VISIT_LOGGED' | 'VISIT_APPROVED' | 'VISIT_REJECTED'
  | 'PTP_CREATED' | 'PTP_FULFILLED' | 'PTP_PARTIALLY_FULFILLED' | 'PTP_BROKEN' | 'PTP_CANCELLED' | 'PTP_RESCHEDULED'
  | 'COLLECTION_SUBMITTED' | 'COLLECTION_APPROVED' | 'COLLECTION_REJECTED' | 'COLLECTION_DEPOSITED' | 'COLLECTION_CANCELLED'
  | 'COMMUNICATION_SENT' | 'COMMUNICATION_SUPPRESSED' | 'COMMUNICATION_FAILED'
  | 'RESTRUCTURE_PROPOSED' | 'RESTRUCTURE_APPROVED' | 'RESTRUCTURE_REJECTED'
  | 'SETTLEMENT_OFFERED' | 'SETTLEMENT_ACCEPTED' | 'SETTLEMENT_DECLINED';

export interface CaseEventResponse {
  timestamp: string;
  eventType: CaseEventType;
  summary: string;
  actorRole?: string;
  actorId?: string;
  actorName?: string;
  sourceTable: string;
  sourceId: string;
  data?: Record<string, unknown>;
  narrative?: string;
}

export interface CaseTimelineResponse {
  allocationId: string;
  organizationId: string;
  loanNumber: string;
  borrowerName: string;
  currentStatus: AllocationStatus;
  npaFlagged?: boolean;
  totalDue?: number;
  outstandingAmount?: number;
  events: CaseEventResponse[];
}

// ── Visit logs ──────────────────────────────────────────────────────────────

export type Contactability =
  | 'CONTACTED_AT_RESIDENCE' | 'CONTACTABLE_AT_BOTH_PLACES' | 'NON_CONTACTABLE'
  | 'CONTACTED_AT_OFFICE' | 'CONTACTABLE_ON_PHONE_ONLY';

export type ClassificationCode =
  | 'WORKABLE_1_INHOUSE_FIELD' | 'WORKABLE_3_HARD_ACCOUNTS_LEGAL_AGGRESSIVE' | 'NON_WORKABLE_NC_SKIP';

export type ReasonForDefault =
  | 'BUSINESS_CLOSED' | 'CUSTOMER_ABSCONDING' | 'BUSINESS_SLOW_DOWN' | 'TEMPORARY_FINANCIAL_PROBLEM'
  | 'CUSTOMER_ABSCONDING_NC_SKIP' | 'INTENTIONAL_DEFAULTER';

export type ResidenceStatus =
  | 'AVAILABLE' | 'LOCKED' | 'SHIFTED_NEW_ADDRESS_NOT_AVAILABLE' | 'AVAILABLE_AND_RESIDING'
  | 'RESIDING' | 'ADDRESS_NOT_TRACED' | 'ONLY_FAMILY_MEMBERS_RESIDING';

export type OfficeStatus =
  | 'ADDRESS_NOT_TRACED' | 'BUSINESS_CLOSED' | 'ACTIVE_BUSINESS_SAME_PLACE' | 'BUSINESS_RUNNING_SAME_PLACE' | 'ACTIVE';

export type VisitStatus = 'GPS_UNAVAILABLE' | 'NOT_VERIFIED' | 'VERIFIED' | 'GPS_MISMATCH' | 'MOCK_LOCATION_DETECTED';

export interface VisitLogRequest {
  allocationId: string;
  assignmentId?: string;
  visitDate: string;
  contactability?: Contactability;
  contactPerson?: string;
  contactNumber?: string;
  disp?: Disp;
  nextVisitDate?: string;
  rescheduleReason?: string;
  classificationCode?: ClassificationCode;
  reasonForDefault?: ReasonForDefault;
  amountCollected?: number;
  paymentMode?: PaymentMode;
  latitude: number;
  longitude: number;
  gpsAccuracy?: number;
  gpsAltitude?: number;
  gpsAddress?: string;
  mockLocationDetected?: boolean;
  residenceStatus?: ResidenceStatus;
  officeStatus?: OfficeStatus;
  customerProfile?: string;
  fieldUpdateFeedback?: string;
  lastVisitedAddress?: string;
  visitNotes?: string;
  internalRemarks?: string;
  afterHoursOverrideReason?: string;
}

export interface VisitImage {
  id: string;
  sequenceNumber: number;
  imagePath?: string;
  s3Key?: string;
  originalFilename?: string;
  uploadStatus: string;
}

export interface VisitLogResponse {
  id: string;
  allocationId: string;
  assignmentId?: string;
  agentId: string;
  agentName?: string;
  organizationId: string;
  loanNumber?: string;
  borrowerName?: string;
  visitDate: string;
  visitTime?: string;
  contactability?: Contactability;
  contactPerson?: string;
  contactNumber?: string;
  disp?: Disp;
  visitOutcome?: string;
  nextVisitDate?: string;
  rescheduleReason?: string;
  classificationCode?: ClassificationCode;
  reasonForDefault?: ReasonForDefault;
  approvalStatus: 'PENDING' | 'APPROVED' | 'REJECTED';
  approvalRemarks?: string;
  approvedBy?: string;
  approvedAt?: string;
  collectionId?: string;
  ptpId?: string;
  amountCollected?: number;
  paymentMode?: string;
  latitude?: number;
  longitude?: number;
  gpsAccuracy?: number;
  gpsAddress?: string;
  visitStatus: VisitStatus;
  mockLocationDetected?: boolean;
  residenceStatus?: ResidenceStatus;
  officeStatus?: OfficeStatus;
  customerProfile?: string;
  fieldUpdateFeedback?: string;
  lastVisitedAddress?: string;
  images: VisitImage[];
  visitNotes?: string;
  internalRemarks?: string;
  createdBy: string;
  createdAt: string;
  updatedAt?: string;
}

// ── PTPs ────────────────────────────────────────────────────────────────────

export interface CreatePtpRequest {
  allocationId: string;
  agentId: string;
  agentName: string;
  loanNumber: string;
  borrowerName: string;
  promisedDate: string;
  promisedAmount: number;
  contactNotes?: string;
  visitId?: string;
}

export interface UpdatePtpStatusRequest {
  newStatus: PtpStatus;
  collectedAmount?: number;
  reason?: string;
}

export interface PtpResponse {
  id: string;
  allocationId: string;
  agentId: string;
  agentName: string;
  loanNumber: string;
  borrowerName: string;
  promisedDate: string;
  promisedAmount: number;
  collectedAmount: number;
  status: PtpStatus;
  contactNotes?: string;
  cancellationReason?: string;
  brokenReason?: string;
  reminderSent: boolean;
  reminderSentAt?: string;
  fulfilledAt?: string;
  brokenAt?: string;
  createdBy: string;
  updatedBy?: string;
  createdAt: string;
  updatedAt?: string;
  fulfillmentPercentage: number;
}

export interface PtpFilterParams {
  allocationId?: string;
  status?: PtpStatus;
  promisedDateFrom?: string;
  promisedDateTo?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  dir?: 'ASC' | 'DESC';
}

// ── Collections ──────────────────────────────────────────────────────────────

export interface SubmitCollectionRequest {
  allocationId: string;
  organizationId: string;
  amount: number;
  paymentMode: PaymentMode;
  collectionDate: string;
  notes?: string;
  idempotencyKey: string;
  visitId: string;
  chequeNumber?: string;
  chequeDate?: string;
  bankName?: string;
  upiReferenceId?: string;
  transactionReferenceId?: string;
  cashHandlingAcknowledged?: boolean;
}

export interface CollectionDocumentResponse {
  id: string;
  fileName: string;
  fileUrl: string;
  documentType?: string;
  uploadedAt: string;
}

export interface CollectionResponse {
  id: string;
  allocationId: string;
  organizationId: string;
  submittedBy: string;
  approvedBy?: string;
  depositedBy?: string;
  amount: number;
  paymentMode: PaymentMode;
  status: CollectionStatus;
  notes?: string;
  rejectionReason?: string;
  receiptNumber?: string;
  collectionDate: string;
  approvedAt?: string;
  depositedAt?: string;
  chequeNumber?: string;
  chequeDate?: string;
  bankName?: string;
  upiReferenceId?: string;
  transactionReferenceId?: string;
  documents?: CollectionDocumentResponse[];
  createdAt: string;
  updatedAt?: string;
}

export interface AgentCollectionReport {
  agentId: string;
  reportDate?: string;
  isDailyReport: boolean;
  totalSubmissions: number;
  totalApproved: number;
  totalRejected: number;
  totalDeposited: number;
  totalPending: number;
  totalAmountSubmitted: number;
  totalAmountApproved: number;
  totalAmountDeposited: number;
}

// ── Payment links ────────────────────────────────────────────────────────────

export type PaymentIntentStatus =
  | 'CREATED' | 'AUTHORIZED' | 'CAPTURED' | 'RECONCILED' | 'REFUNDED' | 'DISPUTED' | 'FAILED' | 'EXPIRED' | 'CANCELLED';

export type PaymentRail = 'UPI' | 'UPI_AUTOPAY' | 'NACH' | 'EMANDATE' | 'NEFT' | 'RTGS' | 'CHEQUE' | 'CASH';
export type PaymentChannel = 'SMS' | 'WHATSAPP' | 'RCS' | 'EMAIL' | 'VOICE_IVR' | 'VOICE_AGENT';

export interface CreatePaymentIntentRequest {
  organizationId: string;
  allocationId: string;
  borrowerId?: string;
  amount: number;
  purpose?: string;
  expiresAt?: string;
}

export interface PaymentIntentResponse {
  id: string;
  organizationId: string;
  allocationId: string;
  borrowerId?: string;
  amount: number;
  currency: string;
  purpose?: string;
  status: PaymentIntentStatus;
  expiresAt?: string;
  idempotencyKey: string;
  createdAt: string;
}

export interface CreatePaymentLinkRequest {
  intentId: string;
  rail: PaymentRail;
  issuedViaChannel?: PaymentChannel;
  expiresAt?: string;
}

export interface PaymentLinkResponse {
  id: string;
  intentId: string;
  token: string;
  shortUrl?: string;
  targetUri?: string;
  issuedViaChannel?: PaymentChannel;
  singleUse: boolean;
  expiresAt?: string;
  consumedAt?: string;
  createdAt: string;
}

// ── Calls ────────────────────────────────────────────────────────────────────

export type CallOutcome =
  | 'ANSWERED' | 'NO_ANSWER' | 'BUSY' | 'WRONG_NUMBER'
  | 'CALLBACK_REQUESTED' | 'REFUSED' | 'SWITCHED_OFF';

export type RecordingStatus = 'PENDING' | 'UPLOADED' | 'FAILED' | 'NOT_RECORDED';

export interface CallStartResponse {
  callLogId: string;
  phoneNumber: string;
}

export interface CompleteCallRequest {
  outcome: CallOutcome;
  notes?: string;
  durationSeconds?: number;
}

export interface CallLogResponse {
  id: string;
  allocationId: string;
  loanNumber?: string;
  borrowerName?: string;
  agentId: string;
  agentName?: string;
  initiatedAt: string;
  endedAt?: string;
  durationSeconds?: number;
  outcome?: CallOutcome;
  phoneMasked?: string;
  notes?: string;
  recordingStatus?: RecordingStatus;
}

// ── Attendance ───────────────────────────────────────────────────────────────

export interface AttendanceCheckInRequest {
  lat?: number;
  lng?: number;
  accuracy?: number;
}

export interface AttendanceResponse {
  id: string;
  userId: string;
  userName: string;
  attendanceDate: string;
  checkedInAt: string;
  lat?: number;
  lng?: number;
  accuracy?: number;
  alreadyRecorded: boolean;
}

// ── Notifications ────────────────────────────────────────────────────────────

export type NotificationType =
  | 'PLATFORM_ORG_ONBOARDED' | 'PLATFORM_SYSTEM_OUTAGE' | 'PLATFORM_SUSPICIOUS_LOGIN' | 'PLATFORM_MASS_DATA_EXPORT'
  | 'ORG_CALLING_HOURS_VIOLATION' | 'ORG_FREQUENCY_CAP_BREACH' | 'ORG_SOS_TRIGGERED' | 'ORG_HIGH_VALUE_PTP_BROKEN'
  | 'ORG_ALLOCATION_UPLOAD_DONE' | 'ORG_APPROVAL_PENDING_OVERDUE' | 'TL_TEAM_SOS' | 'TL_APPROVAL_REQUESTED'
  | 'TL_VISIT_FAILED' | 'TL_CASES_UNASSIGNED' | 'FO_CASE_ASSIGNED' | 'FO_VISIT_ASSIGNED' | 'FO_DISPATCH_READY'
  | 'FO_PTP_EXPIRING_SOON' | 'FO_PTP_BROKEN' | 'FO_COLLECTION_RECORDED' | 'FO_SHIFT_STARTING' | 'FO_DO_NOT_CONTACT'
  | 'MENTION' | 'APPROVAL_DECIDED' | 'REPORT_READY' | 'USER_REQUEST_DECIDED';

// ── Field-agent dashboard (single purpose-built "my day" endpoint) ─────────
// GET /api/v1/dashboard/field-agent/{agentId} — the same endpoint the web
// app's own Dashboard.tsx calls for FO users (dashboardApi.fieldAgent).

export interface TodayAssignmentEntry {
  assignmentId: string;
  allocationId: string;
  loanNumber: string;
  borrowerName: string;
  status: 'COMPLETED' | 'PENDING';
  priorityLevel: 'HIGH' | 'NORMAL';
  sequenceOrder?: number;
  npaFlagged: boolean;
}

export interface FieldAgentDashboardResponse {
  agentId: string;
  agentFirstName: string;
  agentLastName: string;
  today: string;
  todayTotalCases: number;
  todayCompletedCases: number;
  todayPendingCases: number;
  todayRescheduledCases: number;
  todayCompletionRate: number;
  collectedAmountToday: number;
  collectionsSubmittedToday: number;
  pendingPtps: number;
  ptpsDueToday: number;
  todayAssignments: TodayAssignmentEntry[];
}

export interface ServerNotification {
  id: string;
  type: NotificationType;
  priority: 'P0' | 'P1' | 'P2' | 'P3';
  title: string;
  body?: string;
  deepLink?: string;
  payloadJson?: string;
  createdAt: string;
  readAt?: string;
  snoozedUntil?: string;
  dismissed?: boolean;
}

export interface TrendPoint {
  year: number;
  month: number;
  label: string;
  totalAmount: number;
  totalCount: number;
}

export interface TopAgentPoint {
  name: string;
  amount: number;
  count: number;
}

export interface TrendPoint {
  year: number;
  month: number;
  label: string;
  totalAmount: number;
  totalCount: number;
}

export interface TopAgentPoint {
  name: string;
  amount: number;
  count: number;
}

export interface CollectionsSection {
  collectionVolumeThisMonth: number;
  collectionCountThisMonth: number;
  collectionVolumeLastMonth: number;
  growthRate?: number;
  monthlyTrend: TrendPoint[];
  ptpMonthlyTrend?: TrendPoint[];
  visitMonthlyTrend?: TrendPoint[];
  topAgents?: TopAgentPoint[];
}

export interface OrgOverviewSection {
  organizationId: string;
  organizationName: string;
  totalAllocations: number;
  assignedAllocations: number;
  unassignedAllocations: number;
  totalUsers: number;
  collectionVolumeThisMonth: number;
  collectionsThisMonth: number;
  outstandingTotal?: number;
}

export interface OrgTodaySection {
  collectedToday: number;
  collectedYesterday: number;
  visitsTotalToday: number;
  visitsCompletedToday: number;
  visitsInProgressToday: number;
  visitsPendingToday: number;
  fosTotalCount: number;
  fosActiveToday: number;
  fosIdleToday: number;
  ptpsDueToday: number;
  ptpAmountDueToday: number;
}

export interface CallerSection {
  casesAssignedToday: number;
  callsCompletedToday: number;
  callsPendingToday: number;
  ptpsMadeToday: number;
  amountCommittedToday: number;
}

export interface CaseAgeBucket {
  label: string;
  count: number;
}

export interface AllocationsSection {
  totalAllocations: number;
  assignedCount: number;
  unassignedCount: number;
  npaFlaggedCount: number;
  caseAgeBuckets?: CaseAgeBucket[];
}

export interface PtpSummarySection {
  activePtps: number;
  brokenPtpsThisMonth: number;
  fulfilledPtpsThisMonth: number;
  fulfillmentRatePct: number;
}

export interface TeamSection {
  totalUsers: number;
  activeUsers: number;
  usersByRole: { roleName: string; count: number }[];
}

export interface UnifiedDashboardResponse {
  role: string;
  organizationId?: string;
  generatedAt: string;
  orgOverview?: OrgOverviewSection;
  orgToday?: OrgTodaySection;
  collections?: CollectionsSection;
  allocations?: AllocationsSection;
  ptpSummary?: PtpSummarySection;
  team?: TeamSection;
  callerToday?: CallerSection;
}
