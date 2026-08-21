import { useCallback, useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { fraudCasesApi } from '../api/fraudCasesApi';
import { useAuth } from '../AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import type { FraudCaseResponse, FraudCaseStatus } from '../types';
import { ShieldAlert, RefreshCw, Plus, ChevronRight } from 'lucide-react';
import FraudCaseDetailDrawer from './FraudCaseDetailDrawer';
import { FraudCaseCreateModal } from './FraudCaseCreateModal';
import { FRAUD_PILL } from './BorrowersHelpers';
import { fmtDate, fmtCurrency } from './LoanDetailHelpers';
import { Pagination } from '../components/Pagination';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';

const PAGE_SIZE = 25;

const STATUS_OPTIONS: Array<{ value: FraudCaseStatus | ''; label: string }> = [
  { value: '', label: 'All statuses' },
  { value: 'REPORTED', label: 'Reported' },
  { value: 'UNDER_INVESTIGATION', label: 'Under investigation' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CLOSED', label: 'Closed' },
];

const CATEGORY_LABEL: Record<string, string> = {
  CHEATING_AND_FORGERY: 'Cheating & forgery',
  MISAPPROPRIATION: 'Misappropriation',
  FRAUDULENT_ENCASHMENT: 'Fraudulent encashment',
  UNAUTHORISED_CREDIT_FACILITIES: 'Unauthorised credit',
  DOCUMENTATION_FRAUD: 'Documentation fraud',
  CYBER_FRAUD: 'Cyber fraud',
  OTHER: 'Other',
};

const stagger: Variants = { hidden: {}, show: { transition: { staggerChildren: 0.04, delayChildren: 0.02 } } };
const fadeUp: Variants = { hidden: { opacity: 0 }, show: { opacity: 1, transition: { duration: 0.35, ease: 'easeOut' } } };
const fadeIn: Variants = { hidden: { opacity: 0 }, show: { opacity: 1, transition: { duration: 0.22, ease: 'easeOut' } } };

export default function FraudCasesPage() {
  const { user } = useAuth();
  const { hasRole } = usePermissions();
  const canTransition = hasRole('ORG_ADMIN') || hasRole('PLATFORM_ADMIN');
  const organizationId = user?.organizationId || '';

  const [cases, setCases]                 = useState<FraudCaseResponse[]>([]);
  const [loading, setLoading]             = useState(true);
  const [loadError, setLoadError]         = useState(false);
  const [page, setPage]                   = useState(0);
  const [totalPages, setTotalPages]       = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [statusFilter, setStatusFilter]   = useState<FraudCaseStatus | ''>('');
  const [selected, setSelected]           = useState<FraudCaseResponse | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);

  const abortRef = useRef<AbortController | null>(null);

  const fetchCases = useCallback(async () => {
    if (!organizationId) return;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true); setLoadError(false);
    try {
      const data = await fraudCasesApi.list({
        orgId: organizationId,
        status: statusFilter || undefined,
        page, size: PAGE_SIZE,
      }, controller.signal);
      setCases(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err: any) {
      if (err?.name !== 'CanceledError' && err?.code !== 'ERR_CANCELED') setLoadError(true);
    } finally {
      if (abortRef.current === controller) setLoading(false);
    }
  }, [organizationId, statusFilter, page]);

  useEffect(() => { fetchCases(); }, [fetchCases]);

  const handleChanged = (updated: FraudCaseResponse) => {
    setSelected(updated);
    setCases(prev => prev.map(c => (c.id === updated.id ? updated : c)));
  };

  return (
    <div className="db-root db-fill-root" style={{ height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <div className="db-content" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', flex: 1, paddingBottom: 36 }}>
        <div className="db-page-header">
          <div className="db-page-header-left">
            {!loading && (
              <p className="dd-page-context">
                You have <strong>{totalElements.toLocaleString('en-IN')} fraud cases</strong> registered on file.
              </p>
            )}
          </div>
          <div className="db-list-page-actions">
            <select
              className="ds-select"
              value={statusFilter}
              onChange={(e) => { setStatusFilter(e.target.value as FraudCaseStatus | ''); setPage(0); }}
              style={{ width: 'auto', margin: 0 }}
            >
              {STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <div className="db-list-btn-group">
              <button type="button" onClick={fetchCases} disabled={loading} className="ds-btn is-secondary" aria-label="Refresh" title="Refresh">
                <RefreshCw size={14} className={loading ? 'ds-spin' : ''} /> Refresh
              </button>
              {canTransition && (
                <button type="button" onClick={() => setShowCreateModal(true)} className="ds-btn is-primary">
                  <Plus size={14} /> Report case
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
                    <th style={{ width: '20%' }}>Category</th>
                    <th style={{ width: '15%' }}>Case Number</th>
                    <th style={{ width: '15%' }}>Status</th>
                    <th style={{ width: '15%' }}>Reported</th>
                    <th className="is-right" style={{ width: '15%' }}>Amount</th>
                    <th className="is-right" style={{ width: '10%' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {loadError ? (
                    <tr>
                      <td colSpan={6}>
                        <div className="ds-empty" style={{ padding: '60px 0' }}>
                          <span className="ds-empty-title">Fraud cases could not be loaded.</span>
                          <div className="ds-empty-actions" style={{ marginTop: 12 }}>
                            <button type="button" onClick={fetchCases} className="ds-btn is-secondary">Retry</button>
                          </div>
                        </div>
                      </td>
                    </tr>
                  ) : loading ? (
                    Array.from({ length: 8 }).map((_, i) => (
                      <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                        {Array.from({ length: 6 }).map((__, j) => (
                          <td key={j}><div className="ds-skel" style={{ height: 14, width: j === 0 ? '75%' : '60%' }} /></td>
                        ))}
                      </tr>
                    ))
                  ) : cases.length === 0 ? (
                    <tr>
                      <td colSpan={6}>
                        <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show" style={{ padding: '60px 0' }}>
                          <ShieldAlert size={32} className="ds-empty-icon" />
                          <span className="ds-empty-title">No fraud cases on file</span>
                          <span className="ds-empty-sub">
                            {statusFilter ? 'No cases match this status filter.' : 'Cases reported under the RBI Master Direction on Frauds will appear here.'}
                          </span>
                        </motion.div>
                      </td>
                    </tr>
                  ) : (
                    cases.map((c) => (
                      <tr key={c.id} style={{ background: selected?.id === c.id ? 'var(--bg-active)' : undefined }}>
                        <td>
                          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontWeight: 600, color: 'var(--ink-primary)' }}>
                            <ShieldAlert size={14} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
                            {CATEGORY_LABEL[c.category] || c.category}
                          </div>
                        </td>
                        <td className="is-mono is-muted">{c.caseNumber}</td>
                        <td>
                          <span className={`ds-pill ${FRAUD_PILL[c.status]}`}>{c.status.replace(/_/g, ' ')}</span>
                        </td>
                        <td className="is-mono is-muted">{fmtDate(c.reportedAt)}</td>
                        <td className="is-right is-mono is-muted">
                          {c.amountInvolved != null ? fmtCurrency(c.amountInvolved) : '—'}
                        </td>
                        <td className="is-right">
                          <button type="button" onClick={() => setSelected(c)} className="ds-btn is-secondary is-sm">
                            View case
                          </button>
                        </td>
                      </tr>
                    ))
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
          <FraudCaseDetailDrawer
            fraudCase={selected}
            onClose={() => setSelected(null)}
            onChanged={handleChanged}
            canTransition={canTransition}
          />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {showCreateModal && (
          <FraudCaseCreateModal
            onClose={() => setShowCreateModal(false)}
            onSuccess={() => { setShowCreateModal(false); fetchCases(); }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
