import { useCallback, useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { reconciliationApi } from '../api/reconciliationApi';
import { useAuth } from '../AuthContext';
import type { ReconciliationRunResponse } from '../types';
import { Landmark, RefreshCw, UploadCloud, ChevronRight } from 'lucide-react';
import ReconciliationRunDetailDrawer from './ReconciliationRunDetailDrawer';
import { ReconciliationIngestModal } from './ReconciliationIngestModal';
import { fmtDate, fmtDT } from './LoanDetailHelpers';
import { Pagination } from '../components/Pagination';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';

const PAGE_SIZE = 20;

const stagger: Variants = { hidden: {}, show: { transition: { staggerChildren: 0.04, delayChildren: 0.02 } } };
const fadeUp: Variants = { hidden: { opacity: 0 }, show: { opacity: 1, transition: { duration: 0.35, ease: 'easeOut' } } };
const fadeIn: Variants = { hidden: { opacity: 0 }, show: { opacity: 1, transition: { duration: 0.22, ease: 'easeOut' } } };

export default function ReconciliationPage() {
  const { user } = useAuth();
  const organizationId = user?.organizationId || '';

  const [runs, setRuns]                   = useState<ReconciliationRunResponse[]>([]);
  const [loading, setLoading]             = useState(true);
  const [loadError, setLoadError]         = useState(false);
  const [page, setPage]                   = useState(0);
  const [totalPages, setTotalPages]       = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [selected, setSelected]           = useState<ReconciliationRunResponse | null>(null);
  const [showIngestModal, setShowIngestModal] = useState(false);

  const abortRef = useRef<AbortController | null>(null);

  const fetchRuns = useCallback(async () => {
    if (!organizationId) return;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true); setLoadError(false);
    try {
      const data = await reconciliationApi.listRuns({ orgId: organizationId, page, size: PAGE_SIZE }, controller.signal);
      setRuns(data.content);
      setTotalPages(data.totalPages);
      setTotalElements(data.totalElements);
    } catch (err: any) {
      if (err?.name !== 'CanceledError' && err?.code !== 'ERR_CANCELED') setLoadError(true);
    } finally {
      if (abortRef.current === controller) setLoading(false);
    }
  }, [organizationId, page]);

  useEffect(() => { fetchRuns(); }, [fetchRuns]);

  const matchRate = (r: ReconciliationRunResponse) =>
    r.rowsIngested > 0 ? `${((r.matched / r.rowsIngested) * 100).toFixed(0)}%` : '—';

  return (
    <div className="db-root db-fill-root" style={{ height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <div className="db-content" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', flex: 1, paddingBottom: 36 }}>
        <div className="db-page-header">
          <div className="db-page-header-left">
            {!loading && (
              <p className="dd-page-context">
                You have <strong>{totalElements.toLocaleString('en-IN')} reconciliation runs</strong> logged in the ledger.
              </p>
            )}
          </div>
          <div className="db-list-page-actions">
            <div className="db-list-btn-group">
              <button type="button" onClick={fetchRuns} disabled={loading} className="ds-btn is-secondary" aria-label="Refresh" title="Refresh">
                <RefreshCw size={14} className={loading ? 'ds-spin' : ''} /> Refresh
              </button>
              <button type="button" onClick={() => setShowIngestModal(true)} className="ds-btn is-primary">
                <UploadCloud size={14} /> Ingest statement
              </button>
            </div>
          </div>
        </div>

        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <motion.div variants={fadeUp} className="ds-table-card" style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
            <div className="ds-table-wrap" style={{ flex: 1, overflow: 'auto' }}>
              <table className="ds-table is-no-row-hover ps-table">
                <thead>
                  <tr>
                    <th style={{ width: '20%' }}>Source</th>
                    <th style={{ width: '15%' }}>As of Date</th>
                    <th className="is-right" style={{ width: '15%' }}>Ingested</th>
                    <th className="is-right" style={{ width: '10%' }}>Matched</th>
                    <th className="is-right" style={{ width: '10%' }}>Exceptions</th>
                    <th className="is-right" style={{ width: '15%' }}>Match Rate</th>
                    <th className="is-right" style={{ width: '15%' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {loadError ? (
                    <tr>
                      <td colSpan={7}>
                        <div className="ds-empty" style={{ padding: '60px 0' }}>
                          <span className="ds-empty-title">Reconciliation runs could not be loaded.</span>
                          <div className="ds-empty-actions" style={{ marginTop: 12 }}>
                            <button type="button" onClick={fetchRuns} className="ds-btn is-secondary">Retry</button>
                          </div>
                        </div>
                      </td>
                    </tr>
                  ) : loading ? (
                    Array.from({ length: 8 }).map((_, i) => (
                      <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                        {Array.from({ length: 7 }).map((__, j) => (
                          <td key={j}><div className="ds-skel" style={{ height: 14, width: j === 0 ? '75%' : '60%' }} /></td>
                        ))}
                      </tr>
                    ))
                  ) : runs.length === 0 ? (
                    <tr>
                      <td colSpan={7}>
                        <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show" style={{ padding: '60px 0' }}>
                          <Landmark size={32} className="ds-empty-icon" />
                          <span className="ds-empty-title">No reconciliation runs yet</span>
                          <span className="ds-empty-sub">Ingest a bank statement to match it against recorded payment transactions.</span>
                          <div className="ds-empty-actions" style={{ marginTop: 12 }}>
                            <button type="button" onClick={() => setShowIngestModal(true)} className="ds-btn is-primary">Ingest statement</button>
                          </div>
                        </motion.div>
                      </td>
                    </tr>
                  ) : (
                    runs.map((r) => (
                      <tr key={r.id} style={{ background: selected?.id === r.id ? 'var(--bg-active)' : undefined }}>
                        <td>
                          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontWeight: 600, color: 'var(--ink-primary)' }}>
                            <Landmark size={14} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
                            {r.source}
                          </div>
                          <div className="is-muted" style={{ fontSize: 11, marginTop: 4 }}>
                            {fmtDT(r.createdAt)}
                          </div>
                        </td>
                        <td className="is-mono is-muted">{fmtDate(r.asOfDate)}</td>
                        <td className="is-right is-mono is-muted">{r.rowsIngested.toLocaleString('en-IN')}</td>
                        <td className="is-right is-mono is-muted">{r.matched.toLocaleString('en-IN')}</td>
                        <td className="is-right is-mono" style={{ color: r.exceptions > 0 ? 'var(--danger)' : 'var(--ink-muted)' }}>
                          {r.exceptions.toLocaleString('en-IN')}
                        </td>
                        <td className="is-right is-mono is-muted">
                          {matchRate(r)}
                        </td>
                        <td className="is-right">
                          <button type="button" onClick={() => setSelected(r)} className="ds-btn is-secondary is-sm">
                            View run
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
                <Pagination currentPage={page} totalPages={totalPages} onPageChange={setPage} totalElements={totalElements} itemLabel="runs" />
              </div>
            )}
          </motion.div>
        </motion.div>
      </div>

      <AnimatePresence>
        {selected && (
          <ReconciliationRunDetailDrawer run={selected} onClose={() => setSelected(null)} />
        )}
      </AnimatePresence>

      <AnimatePresence>
        {showIngestModal && (
          <ReconciliationIngestModal
            onClose={() => setShowIngestModal(false)}
            onSuccess={() => { setShowIngestModal(false); fetchRuns(); }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
