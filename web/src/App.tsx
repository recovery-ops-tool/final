import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { FeatureGate } from './components/FeatureGate';
import { lazy, Suspense, Component } from 'react';
import type { ReactNode, ErrorInfo } from 'react';
import * as Sentry from '@sentry/react';
import { AuthProvider } from './AuthContext';
import ProtectedRoute from './ProtectedRoute';
import AppLayout from './AppLayout';
import { FeatureFlagsProvider } from './contexts/FeatureFlagsContext';
import LoginPage from './pages/LoginPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import PrivacyPolicyPage from './pages/PrivacyPolicyPage';
import TermsPage from './pages/TermsPage';
import Dashboard from './pages/Dashboard';
import LoansPage from './pages/LoansPage';
import LoanDetailPage from './pages/LoanDetailPage';

import CaseAssignmentsPage from './pages/CaseAssignmentsPage';
import CollectionsPage from './pages/CollectionsPage';
import CollectionMomPage from './pages/CollectionMomPage';
import UploadsPage from './pages/UploadsPage';
import AgentsPage from './pages/AgentsPage';
import ReportsPage from './pages/ReportsPage';
import AuditPage from './pages/AuditPage';
import PtpsPage from './pages/PtpsPage';
import LucienPage from './pages/LucienPage';
import AgentDetailPage from './pages/AgentDetailPage';
import ColumnSchemaPage from './pages/ColumnSchemaPage';
import RoleManagementPage from './pages/RoleManagementPage';
import UploadErrorsPage from './pages/UploadErrorsPage';
import UploadDataPage from './pages/UploadDataPage';
import SubscriptionPage from './pages/SubscriptionPage';
import UnassignedCasesPage from './pages/UnassignedCasesPage';
import DailyDispatchPage from './pages/DailyDispatchPage';
import TodayVisitsPage from './pages/TodayVisitsPage';
import VisitSubmitPage from './pages/VisitSubmitPage';
import VisitInterviewPage from './pages/VisitInterviewPage';
import UsersPage from './pages/UsersPage';
import UserRequestsPage from './pages/UserRequestsPage';
import OrganizationSettingsPage from './pages/OrganizationSettingsPage';
import PaymentLinksPage from './pages/PaymentLinksPage';
import BorrowersPage from './pages/BorrowersPage';
import FraudCasesPage from './pages/FraudCasesPage';
import ReconciliationPage from './pages/ReconciliationPage';
import RestructureProposalsPage from './pages/RestructureProposalsPage';
import NonContactablesPage from './pages/NonContactablesPage';
import PortfolioRiskPage from './pages/PortfolioRiskPage';
import LandingPage from './LandingPage';
import DebugProfileSettings from './DebugProfileSettings';
import DownloadPage from './pages/DownloadPage';
import NotificationsPage from './pages/NotificationsPage';
import MyCasesPage from './pages/MyCasesPage';
import AttendancePage from './pages/AttendancePage';
import MyAttendancePage from './pages/MyAttendancePage';
import StartVisitPage from './pages/StartVisitPage';
import LoadingSplash from './components/LoadingSplash';
import NotificationToasts from './components/NotificationToasts';
import NoiseOverlay from './components/NoiseOverlay';
import ConfettiHost from './components/ConfettiHost';
import PageSkeleton from './components/Skeleton';
import WhatsNewModal from './components/WhatsNewModal';
import SplitPaneDrawer from './components/SplitPaneDrawer';
import TourOverlay from './components/TourOverlay';
import type { Role } from './types';


const LucienAdminPage = lazy(() => import('./pages/LucienAdminPage'));
const FeatureFlagsPage = lazy(() => import('./pages/FeatureFlagsPage'));
const CalendarPage = lazy(() => import('./pages/CalendarPage'));
const MessageTemplatesPage = lazy(() => import('./pages/MessageTemplatesPage'));

// Route-split the heaviest pages: Leaflet (LiveTrackPage/FieldOpsPage), xlsx
// (VisitsPage pulls it in via VisitExportModal), and the whole Platform Admin
// console (never loaded by ordinary org users) — previously all four eagerly
// loaded into the same >1.9MB main chunk as every other page.
const LiveTrackPage = lazy(() => import('./pages/LiveTrackPage'));
const FieldOpsPage = lazy(() => import('./pages/FieldOpsPage'));
const VisitsPage = lazy(() => import('./pages/VisitsPage'));
const PlatformSetupPage = lazy(() => import('./pages/PlatformSetupPage'));
const PlatformDashboard = lazy(() => import('./pages/PlatformDashboard'));
const PlatformSubscriptions = lazy(() => import('./pages/PlatformSubscriptions'));
const PlatformRevenueTrendPage = lazy(() => import('./pages/PlatformRevenueTrendPage'));

function PageFallback() {
  return <PageSkeleton />;
}

interface ErrorBoundaryState { hasError: boolean; message?: string }
class AppErrorBoundary extends Component<{ children: ReactNode }, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };
  static getDerivedStateFromError(err: Error): ErrorBoundaryState {
    return { hasError: true, message: err.message };
  }
  componentDidCatch(err: Error, info: ErrorInfo) {
    Sentry.captureException(err, { extra: { componentStack: info.componentStack } });
  }
  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center h-screen gap-4 text-gray-600">
          <p className="text-lg font-medium">Something went wrong.</p>
          <p className="text-sm text-gray-400">{this.state.message}</p>
          <button
            className="px-4 py-2 rounded bg-indigo-600 text-white text-sm"
            onClick={() => { this.setState({ hasError: false }); window.location.reload(); }}
          >
            Reload page
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

// PLATFORM_ADMIN is deliberately absent from these org-workspace tiers: it's a
// separate console (see navConfig.ts), and the backend only grants it real
// cross-org data on the handful of endpoints that route through
// PlatformAdminAccessGuard.beginCrossOrgAccess (audited, reason-required).
// Everywhere else, Postgres RLS scopes platform admin's queries to their own
// seeded platform org, so route access here without the matching backend
// wiring was a dead end, not a privilege. Uploads and Message Templates are
// the two exceptions with real wiring, so they keep PLATFORM_ADMIN in their
// own dedicated role groups below (UPLOAD_ROLES, MESSAGE_TEMPLATE_ROLES).
const ANY_ORG_ROLE: Role[] = [
  'ORG_ADMIN', 'MANAGER', 'TL', 'FO', 'CALLER', 'TRACER',
];

const ORG_LEAD_ROLES: Role[] = [
  'ORG_ADMIN', 'MANAGER', 'TL',
];

const ORG_ADMIN_ROLES: Role[] = ['ORG_ADMIN'];

const PLATFORM_ONLY: Role[] = ['PLATFORM_ADMIN'];

const PAYMENT_ROLES: Role[] = ['ORG_ADMIN', 'FO'];

// Matches FileUploadController.READERS exactly (see UploadsPage.tsx's own
// UPLOAD_READER_ROLES gate) — the audited cross-org bypass is wired for this
// feature, so platform admin keeps route access here.
const UPLOAD_ROLES: Role[] = ['PLATFORM_ADMIN', 'ORG_ADMIN', 'MANAGER', 'TL'];

// Matches MessageTemplateController's audited PlatformAdminAccessGuard wiring.
const MESSAGE_TEMPLATE_ROLES: Role[] = ['PLATFORM_ADMIN', 'ORG_ADMIN'];

function App() {
  return (
    <AppErrorBoundary>
    <AuthProvider>
      <BrowserRouter>
        <NoiseOverlay />
        <ConfettiHost />
        <LoadingSplash />
        <NotificationToasts />
        <WhatsNewModal />
        <SplitPaneDrawer />
        <TourOverlay />
        <Suspense fallback={<PageFallback />}>
          <Routes>
            {/* ── Public routes ── */}
            <Route path="/login" element={<LoginPage />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/privacy" element={<PrivacyPolicyPage />} />
            <Route path="/terms" element={<TermsPage />} />

            {/* ── Authenticated routes with shared layout ── */}
            <Route element={<ProtectedRoute />}>
              {/* Dev-only debug tool — requires login (was previously reachable by anyone). */}
              <Route path="/__debug-profile" element={<DebugProfileSettings />} />

              <Route element={<FeatureFlagsProvider><AppLayout /></FeatureFlagsProvider>}>

                {/* ── /app/* — unified workspace for every org-level role ── */}
                <Route element={<ProtectedRoute allowedRoles={ANY_ORG_ROLE} />}>
                  <Route path="/app/dashboard" element={<Dashboard />} />
                  <Route path="/app/allocations" element={<LoansPage />} />
                  <Route path="/app/allocations/:id" element={<LoanDetailPage />} />
                  <Route path="/app/cases/unassigned" element={<UnassignedCasesPage />} />
                  <Route path="/app/assignments" element={<CaseAssignmentsPage />} />
                  <Route path="/app/collections" element={<CollectionsPage />} />
                  <Route path="/app/collections/trend" element={<CollectionMomPage />} />
                  <Route path="/app/non-contactables" element={<NonContactablesPage />} />
                  <Route path="/app/restructure-proposals" element={<RestructureProposalsPage />} />
                  <Route path="/app/visits" element={<VisitsPage />} />
                  <Route path="/app/ptps" element={<PtpsPage />} />
                  <Route path="/app/today" element={<TodayVisitsPage />} />
                  <Route path="/app/visits/:caseId/submit" element={<VisitSubmitPage />} />
                  <Route path="/app/visits/:caseId/interview" element={<VisitInterviewPage />} />
                  <Route path="/app/reports" element={<ReportsPage />} />
                  <Route path="/app/audit" element={<AuditPage />} />
                  <Route path="/app/lucien" element={<LucienPage />} />
                  <Route path="/app/notifications" element={<NotificationsPage />} />
                  <Route path="/app/my-cases" element={<MyCasesPage />} />
                  <Route path="/app/my-attendance" element={<MyAttendancePage />} />
                  <Route path="/app/start-visit" element={<StartVisitPage />} />
                </Route>

                {/* ── /app/uploads/* — only tier where platform admin keeps route access
                     outside PLATFORM_ONLY, matching FileUploadController's audited
                     cross-org bypass ── */}
                <Route element={<ProtectedRoute allowedRoles={UPLOAD_ROLES} />}>
                  <Route path="/app/uploads" element={<UploadsPage />} />
                  <Route path="/app/uploads/:id/errors" element={<UploadErrorsPage />} />
                  <Route path="/app/uploads/:id/data" element={<UploadDataPage />} />
                </Route>

                {/* ── /app/* lead-only routes (Platform/Org admin/Manager/TL) ── */}
                <Route element={<ProtectedRoute allowedRoles={ORG_LEAD_ROLES} />}>
                  <Route path="/app/dispatch" element={<DailyDispatchPage />} />
                  <Route path="/app/agents" element={<AgentsPage />} />
                  <Route path="/app/agents/:id" element={<AgentDetailPage />} />
                  <Route path="/app/live-track" element={<LiveTrackPage />} />
                  <Route path="/app/field-ops" element={<FieldOpsPage />} />

                  <Route path="/app/settings/schema" element={<ColumnSchemaPage />} />
                  <Route path="/app/attendance" element={<AttendancePage />} />
                  <Route path="/app/calendar" element={<CalendarPage />} />
                  <Route path="/app/portfolio-risk" element={<PortfolioRiskPage />} />
                </Route>

                {/* ── /app/* org-admin routes (PLATFORM_ADMIN / ORG_ADMIN only) ── */}
                <Route element={<ProtectedRoute allowedRoles={ORG_ADMIN_ROLES} />}>
                  <Route path="/app/subscription" element={<SubscriptionPage />} />
                  <Route path="/app/users/requests" element={<UserRequestsPage />} />
                  <Route path="/app/settings/organization" element={<OrganizationSettingsPage />} />
                  <Route path="/app/borrowers" element={<BorrowersPage />} />
                  <Route path="/app/fraud-cases" element={<FraudCasesPage />} />
                  <Route path="/app/reconciliation" element={<ReconciliationPage />} />
                </Route>

                {/* ── /app/users, /app/settings/roles — route access matches ColumnSchemaPage's
                     tier (ORG_LEAD_ROLES): a MANAGER/TL granted USER_CREATE/USER_DELETE/
                     ROLE_ASSIGN via Role Management can reach these pages, whose own
                     hasPermission() checks hide the controls they weren't granted. The real
                     boundary is the backend's matching CAN_CREATE_USER/CAN_ASSIGN_ROLE
                     @PreAuthorize, not this route. ── */}
                <Route element={<ProtectedRoute allowedRoles={ORG_LEAD_ROLES} />}>
                  <Route path="/app/users" element={<UsersPage />} />
                  <Route path="/app/settings/roles" element={<RoleManagementPage />} />
                </Route>

                {/* ── /app/settings/message-templates — the other tier where platform admin
                     keeps route access, matching MessageTemplateController's audited
                     cross-org bypass ── */}
                <Route element={<ProtectedRoute allowedRoles={MESSAGE_TEMPLATE_ROLES} />}>
                  <Route path="/app/settings/message-templates" element={<MessageTemplatesPage />} />
                </Route>

                {/* ── /app/* payment-tooling routes (org admin + FO, matches PaymentController's own gate) ── */}
                <Route element={<ProtectedRoute allowedRoles={PAYMENT_ROLES} />}>
                  <Route path="/app/payments/links" element={<PaymentLinksPage />} />
                </Route>

                {/* ── Platform-only ── */}
                <Route element={<ProtectedRoute allowedRoles={PLATFORM_ONLY} />}>
                  <Route path="/platform/dashboard" element={<PlatformDashboard />} />
                  <Route path="/platform/subscriptions" element={<PlatformSubscriptions />} />
                  <Route path="/platform/revenue-trend" element={<PlatformRevenueTrendPage />} />
                  <Route path="/platform/setup" element={<PlatformSetupPage />} />
                  <Route path="/platform/feature-flags" element={<FeatureFlagsPage />} />
                  <Route path="/app/lucien/admin" element={<FeatureGate flagKey="LUCIEN_AI" hardBlock><LucienAdminPage /></FeatureGate>} />
                </Route>

                {/* ── Legacy redirects so old bookmarks / JWTs don't 404 ── */}
                <Route path="/admin/*" element={<LegacyRedirect prefix="/admin" />} />
                <Route path="/bank/*"  element={<LegacyRedirect prefix="/bank" />} />
                <Route path="/agent/*" element={<LegacyRedirect prefix="/agent" />} />

              </Route>
            </Route>

            {/* Root — landing page; authenticated users get dashboard link in-page */}
            <Route path="/" element={<LandingPage />} />
            <Route path="/download" element={<DownloadPage />} />

            {/* Catch-all */}
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
        </Suspense>
      </BrowserRouter>
    </AuthProvider>
    </AppErrorBoundary>
  );
}

function LegacyRedirect({ prefix }: { prefix: string }) {
  const tail = window.location.pathname.replace(prefix, '') || '/dashboard';
  return <Navigate to={`/app${tail}`} replace />;
}

export default App;
