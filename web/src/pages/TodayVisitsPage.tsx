import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { apiClient, unwrapApiResponse } from '../client';
import { useAuth } from '../AuthContext';
import { dailyDispatchApi } from '../api/dailyDispatchApi';
import type { AllocationResponse, PagedResponse } from '../types';
import { ChevronLeft, ChevronRight, MapPin, RefreshCw, Phone, AlertCircle, Briefcase, X } from 'lucide-react';
import ActiveVisitCard from '../components/ActiveVisitCard';
import ShiftSosCard from '../components/ShiftSosCard';

import '../styles/AppPage.css';
import '../styles/DailyDispatchPage.css';
import '../styles/DailyDispatch.shell.css';
import '../styles/DailyDispatch.cases.css';

const fmtINR = (v?: number | null) =>
  v != null
    ? new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(v)
    : '—';

// ── Motion variants ────────────────────────────────────────────────────────────

const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.05, delayChildren: 0.04 } },
};

const fadeUp: Variants = {
  hidden: { opacity: 0, y: 16 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.40, ease: [0.22, 1, 0.36, 1] as [number, number, number, number] } },
};

const fadeIn: Variants = {
  hidden: { opacity: 0 },
  show:   { opacity: 1, transition: { duration: 0.28, ease: 'easeOut' as const } },
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
    span.className = 'dd-ripple';
    span.style.cssText = `position: absolute; border-radius: 50%; background: color-mix(in srgb, var(--ink-primary) 8%, transparent); transform: scale(0); pointer-events: none; animation: db-ripple-anim 480ms cubic-bezier(0,0,0.2,1) forwards; width:${size}px;height:${size}px;left:${e.clientX - rect.left - size / 2}px;top:${e.clientY - rect.top - size / 2}px`;
    el.appendChild(span);
    span.addEventListener('animationend', () => span.remove(), { once: true });
  }, []);
  return { ref, fire };
}

export default function TodayVisitsPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
  const todayLabel = new Date().toLocaleDateString('en-IN', {
    weekday: 'short', day: 'numeric', month: 'short',
  });

  const [cases, setCases]     = useState<AllocationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [source, setSource]   = useState<'dispatch' | 'fallback'>('dispatch');
  const [error, setError]     = useState<string | null>(null);
  const [page, setPage]       = useState(0);

  const load = useCallback(async () => {
    if (!user?.id) return;
    setLoading(true); setError(null);
    try {
      const resp = await dailyDispatchApi.myList(today);
      setCases(Array.isArray(resp) ? resp : []);
      setSource('dispatch');
    } catch {
      try {
        const { data } = await apiClient.get('/api/v1/allocations', {
          params: { status: 'ASSIGNED', assignedToUserId: user?.id, page: 0, size: 50 },
        });
        const resp = unwrapApiResponse<PagedResponse<AllocationResponse>>(data);
        setCases(resp.content ?? []);
        setSource('fallback');
      } catch {
        setCases([]);
        setError('Failed to load today\'s visits.');
      }
    } finally { setLoading(false); }
  }, [user?.id, today]);

  useEffect(() => { load(); }, [load]);

    const RippleRow = ({ c }: { c: AllocationResponse }) => {
      const { ref, fire } = useRipple<HTMLButtonElement>();
      return (
        <motion.div
          variants={fadeUp}
          className="dd-case-row"
          onClick={() => navigate(`/app/visits/${c.id}/interview`)}
          role="button"
          tabIndex={0}
          onKeyDown={(e: React.KeyboardEvent) => { if (e.key === 'Enter') navigate(`/app/visits/${c.id}/interview`); }}
        >
          <div className="dd-case-info" style={{ flex: '1 1 40%' }}>
            <span className="dd-case-borrower">{c.borrowerName || 'Unknown borrower'}</span>
            <div className="dd-case-meta">
              <span className="dd-case-loan">{c.loanAccountNo || c.loanNumber || '—'}</span>
            </div>
          </div>
          <div style={{ flex: '1 1 25%', display: 'flex', alignItems: 'center' }}>
            <span className={`ds-pill is-${(c.status as string) === 'DONE' ? 'success' : (c.status as string) === 'IN_PROGRESS' ? 'info' : (c.status as string) === 'CANCELLED' ? 'neutral' : 'warning'}`} style={{ fontSize: 11, padding: '2px 6px' }}>
              {c.status}
            </span>
          </div>
          <div className="dd-case-right" style={{ flex: '1 1 35%', justifyContent: 'flex-end', gap: 16 }}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
              <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-secondary)' }}>{c.agentName || 'Unassigned'}</span>
              <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>Field Officer</span>
            </div>
            <ChevronRight size={14} aria-hidden="true" style={{ color: 'var(--text-tertiary)', flexShrink: 0, opacity: 0.6 }} />
          </div>
        </motion.div>
      );
    };

  const isFo = user?.roles?.some(r => r.name === 'ROLE_FO') ?? false;

  return (
    <div className="dd-page">
      {isFo && <ShiftSosCard />}
      <ActiveVisitCard onClosed={load} />
      {/* ── Page Header ── */}
      <div className="dd-page-header">
        <div className="dd-page-titles">
          <h1 className="dd-page-title">Today's Visits</h1>
          <span className="dd-page-context"><strong>{cases.length}</strong> visits scheduled for {todayLabel}</span>
        </div>
        <div className="dd-page-actions">
          <button
            type="button" onClick={load} disabled={loading}
            className="ds-btn is-secondary" aria-label="Refresh" title="Refresh"
          >
            <RefreshCw size={14} className={loading ? 'ds-spin' : ''} /> Refresh
          </button>
        </div>
      </div>

      <AnimatePresence>
        {error && (
          <motion.div key="toast-err" className="dd-error-banner" role="alert" style={{ marginBottom: 16 }}
            initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.22 }}>
            <AlertCircle size={16} aria-hidden="true" className="dd-error-icon" />
            <div className="dd-error-body">
              <span className="dd-error-title">{error}</span>
              <span className="dd-error-sub">Check your connection or contact your manager.</span>
            </div>
            <button className="dd-error-retry" onClick={() => setError(null)} aria-label="Dismiss">
              <X size={14} aria-hidden="true" />
            </button>
          </motion.div>
        )}
        {source === 'fallback' && !loading && cases.length > 0 && (
          <motion.div key="toast-fallback" className="dd-error-banner" style={{ marginBottom: 16, background: 'var(--warning-subtle)', borderColor: 'var(--warning-border)', color: 'var(--warning)' }}
            initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.22 }}>
            <AlertCircle size={16} style={{ color: 'var(--warning)' }} className="dd-error-icon" />
            <div className="dd-error-body">
              <span className="dd-error-title" style={{ color: 'var(--text-primary)' }}>Showing all your active cases.</span>
              <span className="dd-error-sub" style={{ color: 'var(--text-primary)' }}>Your TL hasn't curated a daily dispatch list for today yet.</span>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="dd-main-container">
        <div className="dd-grid">
          <div className="dd-case-panel">
            <div className="ds-card dd-cases-card is-overflow-hidden" style={{ display: 'flex', flexDirection: 'column' }}>
              <div className="dd-cases-head">
                <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)' }}>Your itinerary</span>
              </div>

              <div className="dd-cp-list-wrap">
                <div className="dd-cp-list">
                  {loading ? (
                    Array.from({ length: 6 }).map((_, i) => (
                      <div key={i} className="dd-case-skel">
                        <span className="ds-skel" style={{ width: 32, height: 32, borderRadius: 'var(--radius-sm)', flexShrink: 0 }} />
                        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '6px' }}>
                          <span className="ds-skel" style={{ height: '14px', width: '40%', borderRadius: '4px' }} />
                          <span className="ds-skel" style={{ height: '11px', width: '25%', borderRadius: '4px' }} />
                        </div>
                      </div>
                    ))
                  ) : cases.length === 0 ? (
                    <motion.div variants={fadeIn} initial="hidden" animate="show" className="dd-cp-empty">
                      <div className="dd-cp-empty-icon">
                        <MapPin size={24} />
                      </div>
                      <div className="dd-cp-empty-title">
                        No visits scheduled
                      </div>
                      <div className="dd-cp-empty-sub">
                        Your TL hasn't dispatched any cases for today, or you've finished them all.
                      </div>
                    </motion.div>
                  ) : (
                    <motion.div variants={stagger} initial="hidden" animate="show" style={{ display: 'flex', flexDirection: 'column' }}>
                      {cases.slice(page * 50, (page + 1) * 50).map((c) => (
                        <RippleRow key={c.id} c={c} />
                      ))}
                    </motion.div>
                  )}
                </div>
              </div>

              {cases.length > 50 && !loading && (
                <footer className="up-pagination" style={{ padding: '12px 16px', borderTop: '1px solid var(--border)', background: 'var(--bg-surface)', flexShrink: 0 }}>
                  <span className="up-page-meta">
                    Page <strong>{page + 1}</strong> of <strong>{Math.ceil(cases.length / 50)}</strong>
                    {' · '}<strong>{cases.length}</strong> cases
                  </span>
                  <div style={{ display: 'flex', gap: 4, marginLeft: 'auto' }}>
                    <button
                      type="button"
                      className="up-page-btn"
                      onClick={() => setPage(p => Math.max(0, p - 1))}
                      disabled={page === 0}
                      aria-label="Previous page"
                    >
                      <ChevronLeft size={14} />
                    </button>
                    <button
                      type="button"
                      className="up-page-btn"
                      onClick={() => setPage(p => Math.min(Math.ceil(cases.length / 50) - 1, p + 1))}
                      disabled={page >= Math.ceil(cases.length / 50) - 1}
                      aria-label="Next page"
                    >
                      <ChevronRight size={14} />
                    </button>
                  </div>
                </footer>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
