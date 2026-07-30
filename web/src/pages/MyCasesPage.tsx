import { useEffect, useMemo, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { Search, X, ChevronRight, AlertCircle, RefreshCw } from 'lucide-react';
import { dailyDispatchApi } from '../api/dailyDispatchApi';
import { allocationsApi } from '../api/allocationsApi';
import { Pagination } from '../components/Pagination';
import { useAuth } from '../AuthContext';
import type { AllocationResponse } from '../types';

import '../styles/AppPage.css';
import '../styles/DailyDispatchPage.css';
import '../styles/DailyDispatch.shell.css';
import '../styles/DailyDispatch.cases.css';

// ── Motion variants ────────────────────────────────────────────────────────────

const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.04, delayChildren: 0.02 } },
};

const fadeUp: Variants = {
  hidden: { opacity: 0, y: 12 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.35, ease: [0.22, 1, 0.36, 1] as [number, number, number, number] } },
};

const fadeIn: Variants = {
  hidden: { opacity: 0 },
  show:   { opacity: 1, transition: { duration: 0.22, ease: 'easeOut' as const } },
};

// ── Formatters ────────────────────────────────────────────────────────────────

function fmtINR(v: number) {
  if (v >= 10_000_000) return `₹${(v / 10_000_000).toFixed(1)}Cr`;
  if (v >= 100_000)    return `₹${(v / 100_000).toFixed(1)}L`;
  if (v >= 1_000)      return `₹${(v / 1_000).toFixed(1)}K`;
  return `₹${Math.round(v)}`;
}

function resolveAmount(c: AllocationResponse): number | null {
  if (typeof c.outstandingAmount === 'number') return c.outstandingAmount;
  if (typeof c.totalDue === 'number') return c.totalDue;
  const dd = c.dynamicData || {};
  const key = Object.keys(dd).find(k => {
    const kl = k.toLowerCase();
    return kl.includes('outstanding') || kl.includes('pos') || kl.includes('balance')
      || (kl.includes('total') && kl.includes('due'));
  });
  if (key != null) {
    const num = Number(String(dd[key]).replace(/[^0-9.\-]/g, ''));
    if (!Number.isNaN(num) && num !== 0) return num;
  }
  return null;
}

function resolveDPD(c: AllocationResponse): number | null {
  const dd = c.dynamicData || {};
  const key = Object.keys(dd).find(k => {
    const kl = k.toLowerCase().replace(/[\s_-]/g, '');
    return kl === 'dpd' || kl === 'dayspastdue' || kl === 'daysoverdue'
      || kl === 'overduedays' || kl === 'dpddays' || kl === 'dpdcount';
  });
  if (key != null) {
    const num = Number(String(dd[key]).replace(/[^0-9]/g, ''));
    if (!Number.isNaN(num)) return num;
  }
  return null;
}

function dpdTone(dpd: number): 'critical' | 'high' | 'warn' | 'neutral' {
  if (dpd <= 30)  return 'neutral';
  if (dpd <= 60)  return 'warn';
  if (dpd <= 90)  return 'high';
  return 'critical';
}

const todayLabel = new Date().toLocaleDateString('en-IN', {
  weekday: 'short', day: 'numeric', month: 'short',
});

// ── Main Page ─────────────────────────────────────────────────────────────────

export default function MyCasesPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [cases,   setCases]   = useState<AllocationResponse[]>([]);
  const [myCases, setMyCases] = useState<AllocationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState(false);
  const [search,  setSearch]  = useState('');
  const [isSearchActive, setIsSearchActive] = useState(false);
  const [page, setPage] = useState(0);
  const PAGE_SIZE = 15;

  const loadData = useCallback(() => {
    if (!user?.id) return;
    setLoading(true);
    setError(false);
    Promise.allSettled([
      dailyDispatchApi.myList(),
      allocationsApi.getAllocations({ status: 'ASSIGNED', assignedToUserId: user.id, page: 0, size: 200 })
    ]).then(([dispRes, casesRes]) => {
      setCases(dispRes.status === 'fulfilled' ? (dispRes.value ?? []) : []);
      setMyCases(casesRes.status === 'fulfilled' ? (casesRes.value.content ?? []) : []);
      if (dispRes.status === 'rejected' && casesRes.status === 'rejected') setError(true);
    }).finally(() => setLoading(false));
  }, [user?.id]);

  useEffect(() => { loadData(); }, [loadData]);

  useEffect(() => { setPage(0); }, [search]);

  const activeList = myCases;

  const filtered = useMemo(() => {
    const base = !search.trim()
      ? activeList
      : activeList.filter(c => {
          const q = search.toLowerCase();
          return c.borrowerName?.toLowerCase().includes(q)
            || c.loanAccountNo?.toLowerCase().includes(q)
            || c.loanNumber?.toLowerCase().includes(q);
        });
    return [...base].sort((a, b) => (resolveDPD(b) ?? -1) - (resolveDPD(a) ?? -1));
  }, [activeList, search]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const pageCases  = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  return (
    <div className="dd-page">

      <div className="dd-page-header">
        <div className="dd-page-titles">
          <h1 className="dd-page-title">My Cases</h1>
          <span className="dd-page-context">
            <><strong>{myCases.length}</strong> total assigned cases</>
          </span>
        </div>
        <div className="dd-page-actions">
          {/* Actions can go here later */}
        </div>
      </div>

      {error && (
        <div className="dd-error-banner" role="alert">
          <AlertCircle size={16} aria-hidden="true" className="dd-error-icon" />
          <div className="dd-error-body">
            <span className="dd-error-title">Could not load your cases.</span>
            <span className="dd-error-sub">Check your connection or try again.</span>
          </div>
          <button className="dd-error-retry" onClick={loadData} aria-label="Retry">
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        </div>
      )}

      <div className="dd-main-container">
        <div className="dd-grid">
          <div className="dd-case-panel">
            <div className="ds-card dd-cases-card is-overflow-hidden">

              <header className="dd-cases-head" style={{ padding: '0 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', height: '48px' }}>
                <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)' }}>All Assigned</span>
                <AnimatePresence mode="popLayout">
                  {!isSearchActive && !search ? (
                    <motion.button
                      key="search-btn"
                      initial={{ opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.8 }} transition={{ duration: 0.15 }}
                      type="button" onClick={() => setIsSearchActive(true)}
                      style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32, border: 'none', background: 'transparent', color: 'var(--ink-tertiary)', cursor: 'pointer', borderRadius: 8, marginLeft: 'auto' }}
                      aria-label="Open search"
                    >
                      <Search size={14} />
                    </motion.button>
                  ) : (
                    <motion.div
                      key="search-bar"
                      initial={{ opacity: 0, width: 32 }} animate={{ opacity: 1, width: 240 }} exit={{ opacity: 0, width: 32 }} transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
                      className="dd-cp-search" style={{ overflow: 'hidden', marginLeft: 'auto', height: 32 }}
                    >
                      <Search size={14} className="dd-agent-search-icon" style={{ flexShrink: 0, color: 'var(--ink-tertiary)', marginLeft: 4 }} />
                      <input
                        autoFocus
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder="Search borrower or loan ID..."
                        style={{ width: '100%', height: '100%', border: 'none', outline: 'none', background: 'transparent' }}
                      />
                      <button
                        type="button" className="dd-cp-search-clear"
                        onClick={() => { setIsSearchActive(false); setSearch(''); }} aria-label="Close search"
                      >
                        <X size={14} />
                      </button>
                    </motion.div>
                  )}
                </AnimatePresence>
              </header>

          <div className="dd-cp-list-wrap">
            <div className="dd-cp-list">
              {loading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <div key={i} className="dd-case-skel">
                    <span className="ds-skel" style={{ width: 32, height: 32, borderRadius: 'var(--radius-sm)', flexShrink: 0 }} />
                    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '6px' }}>
                      <span className="ds-skel" style={{ height: '14px', width: '40%', borderRadius: '4px' }} />
                      <span className="ds-skel" style={{ height: '11px', width: '25%', borderRadius: '4px' }} />
                    </div>
                  </div>
                ))
              ) : filtered.length === 0 ? (
                <motion.div variants={fadeIn} initial="hidden" animate="show" className="dd-cp-empty">
                  <div className="dd-cp-empty-icon">
                    <AlertCircle size={20} />
                  </div>
                  <span className="dd-cp-empty-title">No cases found</span>
                  <span className="dd-cp-empty-sub">
                    {search
                      ? 'Try adjusting your search query.'
                      : 'No cases currently assigned to you.'}
                  </span>
                </motion.div>
              ) : (
                <motion.div variants={stagger} initial="hidden" animate="show" style={{ display: 'flex', flexDirection: 'column' }}>
                  {pageCases.map((c) => {
                    const amt     = resolveAmount(c);
                    const dpd     = resolveDPD(c);
                    const loanRef = c.loanAccountNo || c.loanNumber || '—';
                    const tone    = dpd != null ? dpdTone(dpd) : 'neutral';

                    return (
                      <motion.div
                        key={c.id}
                        variants={fadeUp}
                        className="dd-case-row"
                        onClick={() => navigate(`/app/visits/${c.id}/interview`)}
                        role="button"
                        tabIndex={0}
                        onKeyDown={(e: React.KeyboardEvent) => { if(e.key==='Enter') navigate(`/app/visits/${c.id}/interview`); }}
                      >
                        <div className="dd-case-info">
                          <span className="dd-case-borrower">{c.borrowerName || '—'}</span>
                          <div className="dd-case-meta">
                            <span className="dd-case-loan">{loanRef}</span>
                            {dpd != null && (
                              <span className={`dd-case-dpd is-${tone}`}>DPD {dpd}</span>
                            )}
                          </div>
                        </div>
                        <div className="dd-case-right">
                          <div className="dd-case-amount-col">
                            {amt != null ? (
                              <>
                                <span className="dd-case-amount">{fmtINR(amt)}</span>
                                <span className="dd-case-amount-lbl">POS</span>
                              </>
                            ) : (
                              <span style={{ fontSize: '12.5px', fontFamily: 'var(--font-mono)', color: 'var(--text-tertiary)' }}>—</span>
                            )}
                          </div>
                          <ChevronRight size={14} aria-hidden="true" style={{ color: 'var(--text-tertiary)', flexShrink: 0, opacity: 0.6 }} />
                        </div>
                      </motion.div>
                    );
                  })}
                </motion.div>
              )}
            </div>
          </div>

          <Pagination
            currentPage={page}
            totalPages={totalPages}
            onPageChange={setPage}
            totalElements={filtered.length}
            itemLabel="cases"
          />

            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
