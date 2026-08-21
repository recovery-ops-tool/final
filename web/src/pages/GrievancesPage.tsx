import { useCallback, useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { grievancesApi } from '../api/grievancesApi';
import { usePermissions } from '../hooks/usePermissions';
import type { GrievanceResponse, GrievanceStatus, GrievanceCategory } from '../types/grievances';
import { RefreshCcw, Plus, MessageSquareWarning, ChevronRight } from 'lucide-react';
import GrievanceDetailDrawer from './GrievanceDetailDrawer';
import { GrievanceCreateModal } from './GrievanceCreateModal';
import { fmtDate } from './LoanDetailHelpers';
import { Pagination } from '../components/Pagination';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';

const PAGE_SIZE = 25;

/** Local pill-tone map — Grievances is not wired into BorrowersHelpers.tsx's shared maps. */
export const GRIEVANCE_PILL: Record<GrievanceStatus, string> = {
  RECEIVED: 'is-warn',
  ACKNOWLEDGED: '',
  INVESTIGATING: 'is-info',
  ESCALATED: 'is-error',
  RESOLVED: 'is-accent',
  CLOSED: 'is-neutral',
};

export const GRIEVANCE_CATEGORY_LABEL: Record<GrievanceCategory, string> = {
  HARASSMENT: 'Harassment',
  INCORRECT_INFORMATION: 'Incorrect information',
  RECOVERY_PRACTICE: 'Recovery practice',
  DATA_PRIVACY: 'Data privacy',
  PAYMENT_DISPUTE: 'Payment dispute',
  OTHER: 'Other',
};

export const GRIEVANCE_CATEGORIES: Array<{ value: GrievanceCategory; label: string }> = [
  { value: 'HARASSMENT', label: 'Harassment' },
  { value: 'INCORRECT_INFORMATION', label: 'Incorrect information' },
  { value: 'RECOVERY_PRACTICE', label: 'Recovery practice' },
  { value: 'DATA_PRIVACY', label: 'Data privacy' },
  { value: 'PAYMENT_DISPUTE', label: 'Payment dispute' },
  { value: 'OTHER', label: 'Other' },
];

const STATUS_OPTIONS: Array<{ value: GrievanceStatus | ''; label: string }> = [
  { value: '', label: 'All statuses' },
  { value: 'RECEIVED', label: 'Received' },
  { value: 'ACKNOWLEDGED', label: 'Acknowledged' },
  { value: 'INVESTIGATING', label: 'Investigating' },
  { value: 'ESCALATED', label: 'Escalated' },
  { value: 'RESOLVED', label: 'Resolved' },
  { value: 'CLOSED', label: 'Closed' },
];

const RESOLUTION_PENDING_STATUSES = new Set<GrievanceStatus>(['ACKNOWLEDGED', 'INVESTIGATING', 'ESCALATED']);

const stagger: Variants = { hidden: {}, show: { transition: { staggerChildren: 0.04, delayChildren: 0.02 } } };
const fadeUp: Variants = { hidden: { opacity: 0 }, show: { opacity: 1, transition: { duration: 0.35, ease: 'easeOut' } } };
const fadeIn: Variants = { hidden: { opacity: 0 }, show: { opacity: 1, transition: { duration: 0.22, ease: 'easeOut' } } };

export default function GrievancesPage() {
  const { hasRole } = usePermissions();
  const canRaise = hasRole('FO') || hasRole('CALLER') || hasRole('TL') || hasRole('MANAGER');

  const [grievances, setGrievances]       = useState<GrievanceResponse[]>([]);
  const [loading, setLoading]             = useState(true);
  const [loadError, setLoadError]         = useState(false);
  const [page, setPage]                   = useState(0);
  const [totalPages, setTotalPages]       = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [statusFilter, setStatusFilter]   = useState<GrievanceStatus | ''>('');
  const [selected, setSelected]           = useState<GrievanceResponse | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);

  const abortRef = useRef<AbortController | null>(null);

  const fetchGrievances = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true); setLoadError(false);
    try {
      const data = await grievancesApi.list({ status: statusFilter || undefined, page, size: PAGE_SIZE }, controller.signal);
      setGrievances(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err: any) {
      if (err?.name !== 'CanceledError' && err?.code !== 'ERR_CANCELED') setLoadError(true);
    } finally {
      if (abortRef.current === controller) setLoading(false);
    }
  }, [statusFilter, page]);

  useEffect(() => { fetchGrievances(); }, [fetchGrievances]);

  const handleChanged = (updated: GrievanceResponse) => {
    setSelected(updated);
    setGrievances(prev => prev.map(g => (g.id === updated.id ? updated : g)));
  };

  return (
    <div className="db-root db-fill-root" style={{ height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <div className="db-content" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', flex: 1, paddingBottom: 36 }}>
        <div className="db-page-header">
          <div className="db-page-header-left">
            {!loading && (
              <p className="dd-page-context">
                You have <strong>{totalElements.toLocaleString('en-IN')} grievances</strong> registered on file.
              </p>
            )}
          </div>
          <div className="db-list-page-actions">
            <select
              className="ds-select"
              value={statusFilter}
              onChange={(e) => { setStatusFilter(e.target.value as GrievanceStatus | ''); setPage(0); }}
              style={{ width: 'auto', margin: 0 }}
            >
              {STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <div className="db-list-btn-group">
              <button type="button" onClick={fetchGrievances} disabled={loading} className="ds-btn is-secondary" aria-label="Refresh" title="Refresh">
                <RefreshCcw size={14} className={loading ? 'ds-spin' : ''} /> Refresh
              </button>
              {canRaise && (
                <button type="button" onClick={() => setShowCreateModal(true)} className="ds-btn is-primary">
                  <Plus size={14} /> Raise grievance
                </button>
              )}
            </div>
          </div>
        </div>

        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <motion.div variants={fadeUp} className="ds-table-card" style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
            <div className="ds-table-wrap" style={{ flex: 1, overflow: 'auto' }}>
              <table className="ds-table is-no-row-hover ps-table">
                <thead>
                  <tr>
                    <th style={{ width: '15%' }}>Ticket No.</th>
                    <th style={{ width: '15%' }}>Category</th>
                    <th style={{ width: '25%' }}>Subject</th>
                    <th style={{ width: '15%' }}>Status</th>
                    <th style={{ width: '15%' }}>Raised</th>
                    <th className="is-right" style={{ width: '15%' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {loadError ? (
                    <tr>
                      <td colSpan={6}>
                        <div className="ds-empty" style={{ padding: '60px 0' }}>
                          <span className="ds-empty-title">Grievances could not be loaded.</span>
                          <div className="ds-empty-actions" style={{ marginTop: 12 }}>
                            <button type="button" onClick={fetchGrievances} className="ds-btn is-secondary">Retry</button>
                          </div>
                        </div>
                      </td>
                    </tr>
                  ) : loading ? (
                    Array.from({ length: 8 }).map((_, i) => (
                      <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                        {Array.from({ length: 6 }).map((__, j) => (
                          <td key={j}><div className="ds-skel" style={{ height: 14, width: j === 2 ? '80%' : '60%' }} /></td>
                        ))}
                      </tr>
                    ))
                  ) : grievances.length === 0 ? (
                    <tr>
                      <td colSpan={6}>
                        <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show" style={{ padding: '60px 0' }}>
                          <MessageSquareWarning size={32} className="ds-empty-icon" />
                          <span className="ds-empty-title">No grievances on file</span>
                          <span className="ds-empty-sub">
                            {statusFilter ? 'No grievances match this status filter.' : 'Borrower complaints logged by staff will appear here.'}
                          </span>
                        </motion.div>
                      </td>
                    </tr>
                  ) : (
                    grievances.map((g) => {
                      const now = Date.now();
                      const ackOverdue = g.status === 'RECEIVED' && !!g.acknowledgementDueAt && new Date(g.acknowledgementDueAt).getTime() < now;
                      const resOverdue = RESOLUTION_PENDING_STATUSES.has(g.status) && !!g.resolutionDueAt && new Date(g.resolutionDueAt).getTime() < now;
                      return (
                        <tr key={g.id} style={{ background: selected?.id === g.id ? 'var(--bg-active)' : undefined }}>
                          <td>
                            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontWeight: 600, color: 'var(--ink-primary)' }}>
                              <MessageSquareWarning size={14} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
                              <span className="is-mono">{g.ticketNumber}</span>
                            </div>
                          </td>
                          <td className="is-muted">{GRIEVANCE_CATEGORY_LABEL[g.category] || g.category}</td>
                          <td>
                            <div style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {g.subject}
                            </div>
                          </td>
                          <td>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span className={`ds-pill ${GRIEVANCE_PILL[g.status]}`}>{g.status.replace(/_/g, ' ')}</span>
                              {(ackOverdue || resOverdue) && <span className="ds-pill is-error">Overdue</span>}
                            </div>
                          </td>
                          <td className="is-mono is-muted">{fmtDate(g.createdAt)}</td>
                          <td className="is-right">
                            <button type="button" onClick={() => setSelected(g)} className="ds-btn is-secondary is-sm">
                              View case
                            </button>
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>

            {totalPages > 1 && !loading && (
              <div style={{ padding: '12px 24px', borderTop: '1px solid var(--border-subtle)' }}>
                <Pagination currentPage={page} totalPages={totalPages} onPageChange={setPage} totalElements={totalElements} itemLabel="records" />
              </div>
            )}
          </motion.div>
        </motion.div>
      </div>

      <AnimatePresence>
        {selected && (
          <GrievanceDetailDrawer
            grievance={selected}
            onClose={() => setSelected(null)}
            onChanged={handleChanged}
          />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {showCreateModal && (
          <GrievanceCreateModal
            onClose={() => setShowCreateModal(false)}
            onSuccess={() => { setShowCreateModal(false); fetchGrievances(); }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
