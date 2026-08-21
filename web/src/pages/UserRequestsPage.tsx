import { useState, useEffect, useCallback } from 'react';
import { useAuth } from '../AuthContext';
import { UserPlus, Clock, Plus } from 'lucide-react';
import {
  userRequestsApi, type UserCreationRequest, type RequestedRole, type PagedUserRequests,
} from '../api/userRequestsApi';
import { type Tab, StatusPill, RoleBadge } from './UserRequestsHelpers';
import { UserRequestsReviewModal } from './UserRequestsReviewModal';
import { UserRequestsSubmitModal } from './UserRequestsSubmitModal';
import { Pagination } from '../components/Pagination';
import '../styles/AppPage.css';

export default function UserRequestsPage() {
  const { user } = useAuth();
  const rawRole = user?.roles?.[0]?.name?.replace('ROLE_', '').trim() ?? '';
  const isPlatformAdmin = rawRole === 'PLATFORM_ADMIN';
  const isOrgAdmin = rawRole === 'ORG_ADMIN';
  const canApprove = isPlatformAdmin || isOrgAdmin;
  const canSubmit = isOrgAdmin || isPlatformAdmin;
  const requestedRole: RequestedRole = isPlatformAdmin ? 'ORG_ADMIN' : 'ORG_USER';

  const [tab,            setTab]            = useState<Tab>(canApprove ? 'pending' : 'mine');
  const [pendingData,    setPendingData]    = useState<PagedUserRequests | null>(null);
  const [mineData,       setMineData]       = useState<PagedUserRequests | null>(null);
  const [pendingPage,    setPendingPage]    = useState(0);
  const [minePage,       setMinePage]       = useState(0);
  const [loadingPending, setLoadingPending] = useState(false);
  const [loadingMine,    setLoadingMine]    = useState(false);
  const [reviewTarget,   setReviewTarget]   = useState<UserCreationRequest | null>(null);
  const [showSubmit,     setShowSubmit]     = useState(false);

  const fetchPending = useCallback(async (page: number) => {
    if (!canApprove) return;
    setLoadingPending(true);
    try { setPendingData((await userRequestsApi.listPending(page) as any).data.data); }
    catch { /* ignore */ }
    finally { setLoadingPending(false); }
  }, [canApprove]);

  const fetchMine = useCallback(async (page: number) => {
    setLoadingMine(true);
    try { setMineData((await userRequestsApi.listMine(page) as any).data.data); }
    catch { /* ignore */ }
    finally { setLoadingMine(false); }
  }, []);

  useEffect(() => { fetchPending(pendingPage); }, [fetchPending, pendingPage]);
  useEffect(() => { fetchMine(minePage); }, [fetchMine, minePage]);

  const handleReviewDone = () => { setReviewTarget(null); fetchPending(pendingPage); fetchMine(minePage); };
  const handleSubmitDone = () => { setShowSubmit(false); fetchMine(0); setMinePage(0); setTab('mine'); };

  const activeRows    = tab === 'pending' ? (pendingData?.content ?? []) : (mineData?.content ?? []);
  const activeLoading = tab === 'pending' ? loadingPending : loadingMine;
  const activePaged   = tab === 'pending' ? pendingData : mineData;
  const activePage    = tab === 'pending' ? pendingPage : minePage;
  const setActivePage = tab === 'pending' ? setPendingPage : setMinePage;
  const pendingCount  = pendingData?.totalElements ?? 0;

  return (
    <div className="dd-page db-fill-root">
      <div className="dd-page-header">
        <div className="dd-page-titles" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 4 }}>
          <span className="dd-page-context" style={{ padding: 0 }}>
            {!loadingPending ? (
              <>You have <strong>{pendingCount} pending onboarding requests</strong> awaiting review.</>
            ) : (
              'Manage user onboarding'
            )}
          </span>
        </div>
        <div className="db-list-page-actions">
          {canApprove && (
            <div className="db-trend-range-toggle" style={{ margin: 0 }}>
              <button type="button" onClick={() => setTab('pending')}
                className={`db-trend-range-btn${tab === 'pending' ? ' is-active' : ''}`}
                role="tab" aria-selected={tab === 'pending'}>
                <Clock size={13} style={{ marginRight: 6 }} /> Pending
                {pendingCount > 0 && <span className="ds-pill is-warn" style={{ marginLeft: 6, fontSize: 10 }}>{pendingCount > 99 ? '99+' : pendingCount}</span>}
              </button>
              <button type="button" onClick={() => setTab('mine')}
                className={`db-trend-range-btn${tab === 'mine' ? ' is-active' : ''}`}
                role="tab" aria-selected={tab === 'mine'}>
                <UserPlus size={13} style={{ marginRight: 6 }} /> My requests
              </button>
            </div>
          )}
          <div className="db-list-btn-group">
            {canSubmit && (
              <button type="button" onClick={() => setShowSubmit(true)} className="ds-btn is-primary">
                <Plus size={14} /> New request
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="dd-main-container">
        <div className="dd-grid">
          <div className="dd-case-panel">
            <div className="ds-card dd-cases-card is-overflow-hidden is-list-card" style={{ display: 'flex', flexDirection: 'column' }}>
              <div className="dd-cases-head">
                <h3 className="db-list-title">User Requests</h3>
              </div>

              <div className="dd-cp-list-wrap">
                <div className="dd-cp-list">
                  {activeLoading ? (
                    Array.from({ length: 6 }).map((_, i) => (
                      <div key={i} className="dd-case-skel">
                        <span className="ds-skel" style={{ width: 32, height: 32, borderRadius: 'var(--radius-sm)', flexShrink: 0 }} />
                        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
                          <span className="ds-skel" style={{ height: 14, width: '40%', borderRadius: 4 }} />
                          <span className="ds-skel" style={{ height: 11, width: '25%', borderRadius: 4 }} />
                        </div>
                      </div>
                    ))
                  ) : activeRows.length === 0 ? (
                    <div className="dd-cp-empty">
                      <div className="dd-cp-empty-icon">
                        <UserPlus size={20} />
                      </div>
                      <span className="dd-cp-empty-title">No requests</span>
                      <span className="dd-cp-empty-sub">
                        {tab === 'pending' ? 'No requests pending your approval.' : "You haven't submitted any requests yet."}
                      </span>
                    </div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column' }}>
                      {activeRows.map((req) => (
                        <div key={req.id} className="dd-case-row is-list-row">
                          <div className="dd-case-info">
                            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28, borderRadius: 'var(--radius-sm)', background: 'var(--bg-subtle)', color: 'var(--text-secondary)', flexShrink: 0 }}>
                                <UserPlus size={14} />
                              </div>
                              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                  <span className="dd-case-borrower">
                                    {req.requestedFirstName} {req.requestedLastName}
                                  </span>
                                  <StatusPill status={req.status} />
                                  <RoleBadge role={req.requestedRole} staffRole={req.requestedStaffRole} />
                                </div>
                                <div className="dd-case-meta">
                                  <span className="dd-case-loan">{req.requestedEmail}</span>
                                  <span className="dd-case-loan">• Requested by {req.requestedByName}</span>
                                  <span className="dd-case-loan">• {new Date(req.createdAt).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short' })}</span>
                                </div>
                                {req.status === 'REJECTED' && req.reviewNotes && (
                                  <div style={{ fontSize: 11, color: 'var(--warning)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                    Notes: {req.reviewNotes}
                                  </div>
                                )}
                              </div>
                            </div>
                          </div>
                          <div className="dd-case-right">
                            {tab === 'pending' && canApprove && req.status === 'PENDING' && (
                              <button type="button" onClick={() => setReviewTarget(req)} className="ds-btn is-primary is-sm">
                                Review
                              </button>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>

              {activePaged && activePaged.totalPages > 1 && !activeLoading && (
                <Pagination
                  currentPage={activePage}
                  totalPages={activePaged.totalPages}
                  onPageChange={setActivePage}
                  totalElements={activePaged.totalElements}
                  itemLabel="requests"
                />
              )}
            </div>
          </div>
        </div>
      </div>

      {reviewTarget && <UserRequestsReviewModal request={reviewTarget} onClose={() => setReviewTarget(null)} onDone={handleReviewDone} />}
      {showSubmit && <UserRequestsSubmitModal role={requestedRole} onClose={() => setShowSubmit(false)} onDone={handleSubmitDone} />}
    </div>
  );
}
