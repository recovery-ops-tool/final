import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { apiClient, unwrapApiResponse } from '../client';
import { ptpsApi } from '../api/ptpsApi';
import { useAuth } from '../AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import type { PtpResponse, PtpStatus, PagedResponse } from '../types';
import {
  Phone, RefreshCw, Search, X, Download, SlidersHorizontal, Plus,
  Clock, CheckCircle2, AlertTriangle, Activity
} from 'lucide-react';
import PtpDetailDrawer from './PtpDetailDrawer';
import { PtpCreateModal } from './PtpCreateModal';
import { STATUS_ICON, PtpRow } from './PtpsHelpers';
import { Pagination } from '../components/Pagination';
import '../styles/AppPage.css';
import './Dashboard.css';
import '../styles/PtpsPage.css';

const PAGE_SIZE = 25;

const ALL_STATUSES: PtpStatus[] = ['PENDING', 'FULFILLED', 'PARTIALLY_FULFILLED', 'BROKEN', 'CANCELLED'];
const STATUS_OPTIONS: Array<{ value: PtpStatus | ''; label: string }> = [
  { value: '',                    label: 'All' },
  { value: 'PENDING',             label: 'Pending' },
  { value: 'FULFILLED',           label: 'Fulfilled' },
  { value: 'PARTIALLY_FULFILLED', label: 'Partially fulfilled' },
  { value: 'BROKEN',              label: 'Broken' },
  { value: 'CANCELLED',           label: 'Cancelled' },
];

function csvDownload(filename: string, csv: string) {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
const todayIso      = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
const monthStartIso = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-01`; };
const yearStartIso  = () => `${new Date().getFullYear()}-01-01`;

// ── Motion variants ────────────────────────────────────────────────────────────

const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.04, delayChildren: 0.02 } },
};

const fadeUp: Variants = {
  hidden: { opacity: 0 },
  show:   { opacity: 1, transition: { duration: 0.35, ease: 'easeOut' } },
};

const fadeIn: Variants = {
  hidden: { opacity: 0 },
  show:   { opacity: 1, transition: { duration: 0.22, ease: 'easeOut' as const } },
};

// ── Ripple hook ────────────────────────────────────────────────────────────────

function useRipple<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const fire = useCallback((e: React.MouseEvent) => {
    const el = ref.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const size = Math.max(rect.width, rect.height) * 2.2;
    const span = document.createElement('span');
    span.className = 'db-ripple';
    span.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - rect.left - size / 2}px;top:${e.clientY - rect.top - size / 2}px`;
    el.appendChild(span);
    span.addEventListener('animationend', () => span.remove(), { once: true });
  }, []);
  return { ref, fire };
}

// ── Count-up animation ────────────────────────────────────────────────────────

function useCountUp(target: number, duration = 900) {
  const [val, setVal] = useState(0);
  useEffect(() => {
    if (target === 0) { setVal(0); return; }
    let start: number | null = null;
    const frame = (ts: number) => {
      if (!start) start = ts;
      const p = Math.min((ts - start) / duration, 1);
      const eased = 1 - Math.pow(1 - p, 3);
      setVal(Math.round(eased * target));
      if (p < 1) requestAnimationFrame(frame);
    };
    const id = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(id);
  }, [target, duration]);
  return val;
}

// ── KPI card ──────────────────────────────────────────────────────────────────

function KpiCard({ label, value, footer, icon: Icon, accent, warn, danger, onClick }: {
  label: string; value: string | number;
  footer?: React.ReactNode;
  icon?: React.ElementType; accent?: boolean; warn?: boolean; danger?: boolean;
  onClick?: () => void;
}) {
  const { ref, fire } = useRipple<HTMLButtonElement>();
  return (
    <motion.button ref={ref} type="button" variants={fadeUp}
      className={`db-kpi2-card${accent ? ' is-accent' : ''}${warn ? ' is-warn' : ''}${danger ? ' is-danger' : ''}${onClick ? ' is-hoverable' : ''}`}
      onClick={e => { fire(e); onClick?.(); }} disabled={!onClick}
      whileHover={{ scale: 1.03 }}
      whileTap={{ scale: 0.98 }}
      transition={{ duration: 0.15, ease: 'easeOut' }}
    >
      <div className="db-kpi2-top">
        <span className="db-kpi2-label">{label}</span>
        {Icon && <span className="db-kpi2-icon"><Icon size={14} aria-hidden="true" /></span>}
      </div>
      <div className="db-kpi2-value-row">
        <span className="db-kpi2-value">{value}</span>
      </div>
      {footer && <div className="db-kpi2-footer">{footer}</div>}
    </motion.button>
  );
}

export default function PtpsPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const isBankView  = location.pathname.startsWith('/bank');
  const isAgentView = location.pathname.startsWith('/agent');
  const { hasPermission } = usePermissions();
  const canUpdate = !isBankView && hasPermission('PTP_CREATE');

  const [ptps, setPtps]                   = useState<PtpResponse[]>([]);
  const [loading, setLoading]             = useState(true);
  const [page, setPage]                   = useState(0);
  const [totalPages, setTotalPages]       = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [filterStatus, setFilterStatus]   = useState<PtpStatus | ''>('');
  const [searchInput, setSearchInput]     = useState('');
  const [isSearchOpen, setIsSearchOpen]   = useState(false);
  const [filterOpen, setFilterOpen]       = useState(false);
  const [selectedPtp, setSelectedPtp]     = useState<PtpResponse | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [exporting, setExporting]         = useState(false);
  const [showExportMenu, setShowExportMenu] = useState(false);
  const [customFrom, setCustomFrom]       = useState('');
  const [customTo, setCustomTo]           = useState('');

  const exportMenuRef = useRef<HTMLDivElement>(null);
  const inputRef      = useRef<HTMLInputElement>(null);
  const abortRef      = useRef<AbortController | null>(null);

  // "/" focuses search
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      // The field lives collapsed in the card head now, so open it before
      // focusing — inputRef is null until the expanded branch renders.
      e.preventDefault();
      setIsSearchOpen(true);
      requestAnimationFrame(() => inputRef.current?.focus());
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Escape closes filter modal + export menu
  useEffect(() => {
    if (!filterOpen && !showExportMenu) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setFilterOpen(false); setShowExportMenu(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [filterOpen, showExportMenu]);

  // Click-outside closes export menu
  useEffect(() => {
    if (!showExportMenu) return;
    const onDown = (e: MouseEvent) => {
      if (exportMenuRef.current?.contains(e.target as Node)) return;
      setShowExportMenu(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [showExportMenu]);

  // NOTE: PtpController's GET /api/v1/ptps binds a fixed PtpFilterRequest (allocationId,
  // agentId, status, promisedDateFrom/To, loanNumber, borrowerName, reminderSent) — there is
  // no generic free-text `searchTerm` param, and org scope is always derived from the JWT
  // (an `orgId` query param would be silently ignored). Search is therefore applied
  // client-side against the currently loaded page, the same pattern CollectionsPage uses.
  const fetchPtps = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.append('page', String(page));
      params.append('size', String(PAGE_SIZE));
      if (filterStatus)   params.append('status', filterStatus);
      if (isAgentView && user?.agentId) params.append('agentId', user.agentId);
      const { data } = await apiClient.get('/api/v1/ptps', { params, signal: controller.signal });
      const response = unwrapApiResponse<PagedResponse<PtpResponse>>(data);
      setPtps(response.content);
      setTotalPages(response.totalPages);
      setTotalElements(response.totalElements);
    } catch (err: any) {
      if (err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED' || err?.name === 'AbortError') return;
    } finally {
      if (abortRef.current === controller) setLoading(false);
    }
  }, [page, filterStatus, isAgentView, user?.agentId]);

  useEffect(() => { fetchPtps(); }, [fetchPtps]);

  const visiblePtps = useMemo(() => {
    const q = searchInput.trim().toLowerCase();
    if (!q) return ptps;
    return ptps.filter(p => {
      const fields = [p.loanNumber, p.borrowerName, p.agentName];
      return fields.some(f => typeof f === 'string' && f.toLowerCase().includes(q));
    });
  }, [ptps, searchInput]);

  const statusCounts = ptps.reduce(
    (acc, p) => ({ ...acc, [p.status]: (acc[p.status] || 0) + 1 }),
    {} as Record<string, number>,
  );

  const hasFilters = Boolean(searchInput || filterStatus);
  const clearFilters = () => { setSearchInput(''); setFilterStatus(''); setPage(0); };

  const handleExport = useCallback(async (fromDate?: string, toDate?: string) => {
    setExporting(true); setShowExportMenu(false);
    try {
      const csv = await ptpsApi.exportCsv(fromDate || toDate ? { fromDate, toDate } : undefined);
      csvDownload('ptps.csv', csv);
    } catch { /* silent */ } finally { setExporting(false); }
  }, []);

  const startIdx = page * PAGE_SIZE;
  const endIdx   = Math.min(startIdx + ptps.length, totalElements);

  // Animations for KPI metrics (showing stats across current page as a proxy)
  const pendingCount = useCountUp(statusCounts['PENDING'] || 0);
  const fulfilledCount = useCountUp((statusCounts['FULFILLED'] || 0) + (statusCounts['PARTIALLY_FULFILLED'] || 0));
  const brokenCount = useCountUp(statusCounts['BROKEN'] || 0);

  return (
    <div className="db-root db-fill-root" style={{ height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <div className="db-content" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', flex: 1, paddingBottom: 36 }}>
        <div className="db-page-header">
          <div className="db-page-header-left is-list-header">
            {!loading && (
              <p className="dd-page-context">
                You have <strong>{totalElements.toLocaleString('en-IN')} commitments</strong> with <strong>{pendingCount.toLocaleString('en-IN')} pending</strong>, <strong>{fulfilledCount.toLocaleString('en-IN')} fulfilled</strong> and <strong>{brokenCount.toLocaleString('en-IN')} broken</strong>.
              </p>
            )}
          </div>
          <div className="db-list-page-actions">
            <div className="db-list-btn-group">
              <div ref={exportMenuRef} style={{ position: 'relative' }}>
                <button
                  type="button"
                  onClick={() => setShowExportMenu(v => !v)}
                  disabled={exporting}
                  className="ds-btn is-secondary"
                >
                  <Download size={14} />
                  {exporting ? 'Exporting...' : 'Export'}
                </button>
                <AnimatePresence>
                  {showExportMenu && (
                    <motion.div
                      initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 4 }} transition={{ duration: 0.15 }}
                      style={{ position: 'absolute', top: '100%', right: 0, marginTop: 4, width: 220, background: 'var(--bg-surface)', border: '1px solid var(--border)', borderRadius: 8, padding: 8, boxShadow: 'var(--shadow-md)', zIndex: 100 }}
                    >
                      <p style={{ fontSize: 11, fontWeight: 600, color: 'var(--ink-tertiary)', textTransform: 'uppercase', letterSpacing: '0.04em', margin: '4px 8px 8px' }}>Date range</p>
                      <button type="button" onClick={() => handleExport()} style={{ width: '100%', textAlign: 'left', padding: '8px', fontSize: 13, background: 'transparent', border: 'none', borderRadius: 4, cursor: 'pointer' }} className="is-hoverable">All time</button>
                      <button type="button" onClick={() => handleExport(todayIso(), todayIso())} style={{ width: '100%', textAlign: 'left', padding: '8px', fontSize: 13, background: 'transparent', border: 'none', borderRadius: 4, cursor: 'pointer' }} className="is-hoverable">Today</button>
                      <button type="button" onClick={() => handleExport(monthStartIso(), todayIso())} style={{ width: '100%', textAlign: 'left', padding: '8px', fontSize: 13, background: 'transparent', border: 'none', borderRadius: 4, cursor: 'pointer' }} className="is-hoverable">This month</button>
                      <button type="button" onClick={() => handleExport(yearStartIso(), todayIso())} style={{ width: '100%', textAlign: 'left', padding: '8px', fontSize: 13, background: 'transparent', border: 'none', borderRadius: 4, cursor: 'pointer' }} className="is-hoverable">This year</button>
                      <div style={{ height: 1, background: 'var(--border-subtle)', margin: '8px 0' }} />
                      <p style={{ fontSize: 11, fontWeight: 600, color: 'var(--ink-tertiary)', textTransform: 'uppercase', letterSpacing: '0.04em', margin: '4px 8px 8px' }}>Custom range</p>
                      <input type="date" value={customFrom} onChange={e => setCustomFrom(e.target.value)} className="ds-input" style={{ width: '100%', marginBottom: 4, height: 32, fontSize: 12 }} />
                      <input type="date" value={customTo}   onChange={e => setCustomTo(e.target.value)}   className="ds-input" style={{ width: '100%', marginBottom: 8, height: 32, fontSize: 12 }} />
                      <button type="button" onClick={() => handleExport(customFrom || undefined, customTo || undefined)}
                        className="ds-btn is-primary is-sm" style={{ width: '100%' }}>
                        Export custom range
                      </button>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
              {canUpdate && !isBankView && (
                <button
                  type="button"
                  onClick={() => setShowCreateModal(true)}
                  className="ds-btn is-primary"
                >
                  <Plus size={14} /> New PTP
                </button>
              )}

              <button
                type="button"
                onClick={() => setFilterOpen(true)}
                className={`ds-btn is-secondary db-list-filter-btn${filterOpen || filterStatus ? ' is-active' : ''}`}
              >
                <SlidersHorizontal size={14} />
                Filter
                {filterStatus && <span className="db-list-filter-dot" />}
              </button>

              <button type="button" onClick={fetchPtps} disabled={loading}
                className="ds-btn is-secondary" aria-label="Refresh" title="Refresh">
                <RefreshCw size={14} className={loading ? 'ds-spin' : ''} /> Refresh
              </button>
            </div>
          </div>
        </div>

        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>

          <AnimatePresence>
            {hasFilters && (
              <motion.div variants={fadeUp} style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
                {filterStatus && (
                  <span className="ds-pill is-accent">
                    {STATUS_OPTIONS.find(o => o.value === filterStatus)?.label ?? filterStatus}
                    <button type="button" onClick={() => setFilterStatus('')} aria-label="Clear status filter" style={{ background: 'transparent', border: 'none', marginLeft: 4, cursor: 'pointer', display: 'flex', color: 'inherit' }}>
                      <X size={11} />
                    </button>
                  </span>
                )}
                <button type="button" onClick={clearFilters} className="db-customize-btn" style={{ padding: '0 8px', fontSize: 11 }}>
                  Clear all
                </button>
              </motion.div>
            )}
          </AnimatePresence>

          <motion.section variants={fadeUp} className="ds-card db-card is-list-card" style={{ marginTop: 0, display: 'flex', flexDirection: 'column', ...(visiblePtps.length > 0 ? { flex: 1, minHeight: 0 } : {}) }}>
            <header className="db-card-head db-list-head" style={{ borderBottom: 'none' }}>
              <h3 className="db-list-title">Promise to Pay</h3>

              <div className="db-list-head-actions">
                <AnimatePresence initial={false}>
                  {isSearchOpen ? (
                    <motion.div
                      className="db-list-search"
                      initial={{ width: 0, opacity: 0 }}
                      animate={{ width: 220, opacity: 1 }}
                      exit={{ width: 0, opacity: 0 }}
                      transition={{ duration: 0.2 }}
                    >
                      <Search size={14} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
                      <input
                        ref={inputRef}
                        type="search"
                        value={searchInput}
                        onChange={e => setSearchInput(e.target.value)}
                        placeholder="Search loan number or borrower…"
                        autoComplete="off"
                        spellCheck={false}
                        aria-label="Search PTPs"
                      />
                      <button
                        type="button"
                        className="db-list-search-clear"
                        onClick={() => { setSearchInput(''); setIsSearchOpen(false); }}
                        aria-label="Close search"
                      >
                        <X size={14} />
                      </button>
                    </motion.div>
                  ) : (
                    <motion.button
                      type="button"
                      className="db-list-search-trigger"
                      onClick={() => setIsSearchOpen(true)}
                      title="Search"
                      aria-label="Search PTPs"
                      initial={{ opacity: 0 }}
                      animate={{ opacity: 1 }}
                      exit={{ opacity: 0 }}
                    >
                      <Search size={14} />
                    </motion.button>
                  )}
                </AnimatePresence>
              </div>
            </header>

            <div className="db-list-body db-list-scroll">
              {loading ? (
                <div className="db-list-skel-wrap">
                  {Array.from({ length: 8 }).map((_, i) => (
                    <div key={i} className="dd-case-skel db-list-skel" style={{ opacity: 1 - i * 0.09 }}>
                      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
                        <span className="ds-skel" style={{ height: 16, width: '40%' }} />
                        <span className="ds-skel" style={{ height: 12, width: '25%' }} />
                      </div>
                      <span className="ds-skel" style={{ height: 18, width: 80 }} />
                    </div>
                  ))}
                </div>
              ) : visiblePtps.length === 0 ? (
                <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show">

                  <Phone size={32} className="ds-empty-icon" />
                  <span className="ds-empty-title">No PTP records found</span>
                  <span className="ds-empty-sub">
                    {searchInput
                      ? 'Nothing on the current page matches your search. Clear the search, or change page / status filters.'
                      : hasFilters
                        ? 'No matches for the current filters. Try clearing them.'
                        : 'PTPs will appear here once field officers record promise-to-pay commitments from borrowers.'}
                  </span>
                  {hasFilters && (
                    <div className="ds-empty-actions" style={{ marginTop: 12 }}>
                      <button type="button" onClick={clearFilters} className="ds-btn is-secondary">Clear filters</button>
                    </div>
                  )}
                </motion.div>
              ) : (
                <motion.div variants={stagger} initial="hidden" animate="show">
                  {visiblePtps.map((ptp, idx) => (
                    <PtpRow
                      key={ptp.id}
                      ptp={ptp}
                      isSelected={selectedPtp?.id === ptp.id}
                      onSelect={(ptp) => {
                        const prefix = location.pathname.startsWith('/bank')
                          ? '/bank'
                          : location.pathname.startsWith('/agent')
                          ? '/agent'
                          : '/app';
                        navigate(`${prefix}/ptps/${ptp.id}`);
                      }}
                      variants={{ ...fadeUp, show: { ...fadeUp.show, transition: { ...((fadeUp.show as any)?.transition || {}), delay: idx * 0.03 } } }}
                    />
                  ))}
                </motion.div>
              )}
            </div>

            {/* ── Pagination ── */}
            {totalPages > 1 && !loading && (
              <Pagination
                currentPage={page}
                totalPages={totalPages}
                onPageChange={setPage}
                totalElements={totalElements}
                itemLabel="commitments"
              />
            )}
          </motion.section>
        </motion.div>
      </div>

      {/* ── Filter modal ── */}
      <AnimatePresence>
        {filterOpen && (
          <motion.div className="ds-modal-overlay" onClick={() => setFilterOpen(false)}
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
            <motion.div className="ds-modal" onClick={e => e.stopPropagation()}
              initial={{ scale: 0.95, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} exit={{ scale: 0.95, opacity: 0 }} transition={{ duration: 0.15 }}>
              <div className="ds-modal-header">
                <span className="ds-modal-title">Filter PTPs</span>
                <button type="button" onClick={() => setFilterOpen(false)} className="ds-modal-close" aria-label="Close">
                  <X size={16} />
                </button>
              </div>

              <span className="ds-label" style={{ display: 'block', marginBottom: 10 }}>Status</span>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {STATUS_OPTIONS.map(opt => (
                  <button
                    key={opt.value}
                    type="button"
                    className={`ds-btn is-sm ${filterStatus === opt.value ? 'is-primary' : 'is-secondary'}`}
                    style={filterStatus === opt.value ? { background: 'var(--ink-solid)', color: 'var(--text-on-solid)' } : {}}
                    onClick={() => { setFilterStatus(opt.value as PtpStatus | ''); setPage(0); }}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>

              <div className="ds-modal-actions">
                <button type="button" onClick={() => { clearFilters(); setFilterOpen(false); }} className="ds-btn is-secondary">
                  Clear
                </button>
                <button type="button" onClick={() => setFilterOpen(false)} className="ds-btn is-primary">
                  Apply
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* ── Detail drawer ── */}
      <AnimatePresence>
        {selectedPtp && (
          <PtpDetailDrawer
            ptp={selectedPtp}
            onClose={() => setSelectedPtp(null)}
            onChanged={() => { setSelectedPtp(null); fetchPtps(); }}
            canUpdate={canUpdate}
          />
        )}
      </AnimatePresence>

      {/* ── Create PTP modal ── */}
      <AnimatePresence>
        {showCreateModal && (
          <PtpCreateModal
            onClose={() => setShowCreateModal(false)}
            onSuccess={() => { setShowCreateModal(false); fetchPtps(); }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}

