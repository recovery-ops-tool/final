import { useEffect, useState, useRef, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Loader2, UserCheck, Send, CheckCircle2, Undo2, Check, ArrowUpRight, Search, Wand2, User } from 'lucide-react';
import type { UserResponse, AllocationResponse, OptimizedAssignmentOrderResponse } from '../types';
import { hashColor } from '../utils/navConfig';
import { Modal, ModalFooter } from './PlatformSetupShared';
import { Pagination } from '../components/Pagination';
import { PILL_VARIANT, dynField } from './LoanDetailHelpers';
import '../styles/PlatformSetupPage.css';

interface Props {
  selectedAgent: string;
  activeTab: 'queue' | 'done';
  setActiveTab: (t: 'queue' | 'done') => void;
  undispatchedCases: AllocationResponse[];
  dispatchedCases: AllocationResponse[];
  displayedCases: AllocationResponse[];
  cases: AllocationResponse[];
  dispatched: AllocationResponse[];
  picked: Set<string>;
  setPicked: (s: Set<string>) => void;
  toggle: (id: string) => void;
  fmtINR: (n?: number | null) => string;
  selectedTotal: number;
  undoOne: (id: string) => void;
  undoingId: string | null;
  casesLoading: boolean;
  canDispatch: boolean;
  submitting: boolean;
  doSend: () => void;
  agentObj: UserResponse | undefined;
  initials: (a: UserResponse) => string;
  optimizing: boolean;
  onOptimize: () => void;
  optimizedOrder: string[] | null;
  optimizeMeta: Map<string, OptimizedAssignmentOrderResponse['ordered'][number]> | null;
  canOptimize: boolean;
}

// ── Count-up animation ────────────────────────────────────────────────────────

function useCountUp(target: number, duration = 500) {
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

/** Resolve outstanding amount: top-level fields first, then dynamicData scan. */
export function resolveAmount(c: AllocationResponse): number | null {
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

function resolveProduct(c: AllocationResponse): string | null {
  const dd = c.dynamicData || {};
  const key = Object.keys(dd).find(k => {
    const kl = k.toLowerCase().replace(/[\s_-]/g, '');
    return kl === 'producttype' || kl === 'loantype' || kl === 'product'
      || kl === 'scheme' || kl === 'loancategory' || kl === 'loanproduct';
  });
  if (!key) return null;
  const val = String(dd[key]).trim();
  return val.length > 0 ? val.slice(0, 14) : null;
}

/** Most recent visit disposition — set/updated by the FO from the allocation
 *  detail page, so it can change between renders as visits get logged. */
function resolveDisposition(c: AllocationResponse): string | undefined {
  return c.latestDisposition || dynField(c.dynamicData || {}, ['disposition', 'Disposition', 'DISPOSITION']);
}

function dpdTone(dpd: number): 'neutral' | 'warn' | 'high' | 'critical' {
  if (dpd <= 30)  return 'neutral';
  if (dpd <= 90)  return 'warn';
  if (dpd <= 180) return 'high';
  return 'critical';
}

const IS_APPLE = typeof navigator !== 'undefined'
  && /Mac|iPhone|iPad|iPod/.test(navigator.userAgent);
const DISPATCH_HINT = IS_APPLE ? '⌘↵' : 'Ctrl↵';

export default function DispatchCasePanel(p: Props) {
  const [casePage, setCasePage] = useState(0);
  const [showConfirm, setShowConfirm] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const PAGE_SIZE = 10;
  const selectedTotalAnim = useCountUp(p.selectedTotal, 500);
  const { ref: listRef, fire: ripple } = useRipple<HTMLDivElement>();

  useEffect(() => {
    setCasePage(0);
  }, [p.activeTab, searchQuery]);

  const query = searchQuery.trim().toLowerCase();
  const filteredCases = query
    ? p.displayedCases.filter(c => {
        const name = (c.borrowerName || '').toLowerCase();
        const loanRef = (c.loanAccountNo || c.loanNumber || '').toLowerCase();
        return name.includes(query) || loanRef.includes(query);
      })
    : p.displayedCases;

  const totalCases = filteredCases.length;
  const totalPages = Math.ceil(totalCases / PAGE_SIZE);
  const pageCases = filteredCases.slice(casePage * PAGE_SIZE, (casePage + 1) * PAGE_SIZE);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && p.picked.size > 0 && p.canDispatch && !p.submitting) {
        e.preventDefault();
        setShowConfirm(true);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [p.picked.size, p.canDispatch, p.submitting]);

  const confirmSend = async () => {
    await p.doSend();
    setShowConfirm(false);
  };

  const agentFullName = p.agentObj ? `${p.agentObj.firstName} ${p.agentObj.lastName}`.trim() : '';

  return (
    <div className="dd-cases-card ds-card is-overflow-hidden is-list-card">
      {/* ── Header: tabs (far left) · search (far right) ── */}
      <div className="dd-cases-head">

        <div className="dd-cases-head-left">
          {/* Tabs */}
          <div className="dd-cp-tabs">
            <button
              type="button"
              className={`dd-cp-tab${p.activeTab === 'queue' ? ' is-active' : ''}`}
              onClick={() => p.setActiveTab('queue')}
            >
              Queue
              <span className="dd-cp-tab-count">{p.undispatchedCases.length}</span>
            </button>
            <button
              type="button"
              className={`dd-cp-tab${p.activeTab === 'done' ? ' is-active' : ''}`}
              onClick={() => p.setActiveTab('done')}
            >
              Done
              <span className="dd-cp-tab-count">{p.dispatchedCases.length}</span>
            </button>
          </div>
        </div>

        <div aria-hidden="true" />

        <div className="dd-cases-head-right">
          {/* Search */}
          <AnimatePresence mode="popLayout">
            {!searchOpen ? (
              <motion.button
                key="search-btn"
                initial={{ opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.8 }} transition={{ duration: 0.15 }}
                type="button" onClick={() => setSearchOpen(true)}
                style={{ width: 34, height: 34, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: 'transparent', border: '1px solid transparent', borderRadius: 8, cursor: 'pointer', color: 'var(--ink-secondary)' }}
                aria-label="Open search"
              >
                <Search size={14} />
              </motion.button>
            ) : (
              <motion.div
                key="search-bar"
                initial={{ width: 0, opacity: 0 }} animate={{ width: 220, opacity: 1 }} exit={{ width: 0, opacity: 0 }}
                transition={{ duration: 0.2 }}
                style={{ display: 'flex', alignItems: 'center', background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)', borderRadius: 8, padding: '0 10px', height: 34, overflow: 'hidden' }}
                onBlur={(e) => {
                  const next = e.relatedTarget as Node | null;
                  if (!next || !e.currentTarget.contains(next)) {
                    setSearchOpen(false);
                    setSearchQuery('');
                  }
                }}
              >
                <Search size={14} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
                <input
                  autoFocus
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder="Search borrower or loan ID..."
                  style={{ border: 'none', background: 'transparent', outline: 'none', fontSize: 13, width: '100%', paddingLeft: 8 }}
                />
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>

      {/* ── Case list ── */}
      <div className="dd-cp-list-wrap" ref={listRef}>
        <div className="dd-cp-list">
          <AnimatePresence mode="wait">
            <motion.div
              key={p.activeTab}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.18 }}
              style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}
            >
              {p.casesLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <div key={i} className="dd-case-skel" style={{ opacity: 1 - i * 0.1 }}>
                    <span className="ds-skel" style={{ width: 18, height: 18, borderRadius: 5, flexShrink: 0 }} />
                    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <span className="ds-skel" style={{ height: 14, width: '45%' }} />
                      <span className="ds-skel" style={{ height: 11, width: '65%' }} />
                    </div>
                    <span className="ds-skel" style={{ height: 16, width: 70 }} />
                  </div>
                ))
              ) : !p.selectedAgent ? (
                <div className="ds-empty" style={{ height: '100%', minHeight: 200 }}>
                  <span className="ds-empty-icon">
                    <UserCheck size={20} aria-hidden="true" />
                  </span>
                  <span className="ds-empty-title">Select a field officer</span>
                  <span className="ds-empty-sub">Pick an officer from the list to view their dispatch queue.</span>
                </div>
              ) : pageCases.length === 0 && query ? (
                <div className="ds-empty" style={{ height: '100%', minHeight: 200 }}>
                  <span className="ds-empty-icon">
                    <Search size={20} aria-hidden="true" />
                  </span>
                  <span className="ds-empty-title">No matches</span>
                  <span className="ds-empty-sub">No cases match "{searchQuery.trim()}".</span>
                </div>
              ) : pageCases.length === 0 ? (
                <div className="ds-empty" style={{ height: '100%', minHeight: 200 }}>
                  <span className="ds-empty-icon" style={p.activeTab === 'queue'
                    ? { background: 'var(--success-subtle)', borderColor: 'var(--success-border)', color: 'var(--success)' }
                    : undefined}>
                    {p.activeTab === 'done' ? <Send size={20} /> : <CheckCircle2 size={20} />}
                  </span>
                  <span className="ds-empty-title">
                    {p.activeTab === 'done' ? 'Queue is empty' : 'All clear'}
                  </span>
                  <span className="ds-empty-sub">
                    {p.activeTab === 'done'
                      ? 'No cases dispatched yet for this date.'
                      : 'All assigned cases have been dispatched.'}
                  </span>
                </div>
              ) : (
                <>
                  {pageCases.map((c) => {
                    const isPicked  = p.picked.has(c.id);
                    const isDone    = p.activeTab === 'done';
                    const amt       = resolveAmount(c);
                    const dpd       = resolveDPD(c);
                    const product   = resolveProduct(c);
                    const disposition = resolveDisposition(c);
                    const loanRef   = c.loanAccountNo || c.loanNumber || '—';
                    const rank      = isPicked && p.optimizedOrder ? p.optimizedOrder.indexOf(c.id) : -1;
                    const meta      = rank >= 0 ? p.optimizeMeta?.get(c.id) : undefined;
                    return (
                      <div key={c.id}
                        className={`dd-case-row is-list-row${isPicked ? ' is-picked' : ''}${isDone ? ' is-done' : ''}`}
                        onClick={isDone ? undefined : (e) => { ripple(e as any); p.toggle(c.id); }}
                        style={{ boxShadow: isPicked ? 'none' : undefined }}
                        title={meta?.rationale}
                      >
                        <div className={`dd-case-check${isPicked ? ' is-checked' : ''}${isDone ? ' is-done' : ''}`}>
                          {rank >= 0
                            ? <span style={{ fontSize: 10, fontWeight: 700 }}>{rank + 1}</span>
                            : (isPicked || isDone) && <Check size={12} strokeWidth={3} />
                          }
                        </div>

                        <div className="dd-case-info">
                          <span className="dd-case-borrower">{c.borrowerName || '—'}</span>
                          <div className="dd-case-meta">
                            <span className="dd-case-loan">{loanRef}</span>
                            {dpd != null && (
                              <span className={`dd-case-dpd is-${dpdTone(dpd)}`}>DPD {dpd}</span>
                            )}
                            {product && (
                              <span className="dd-case-product">{product}</span>
                            )}
                            {disposition && (
                              <span className={`ds-pill ${PILL_VARIANT[disposition] ?? ''}`} style={{ fontSize: 9.5, padding: '1px 5px', height: 'auto' }}>
                                {disposition.replace(/_/g, ' ')}
                              </span>
                            )}
                          </div>
                        </div>

                        <div className="dd-case-right">
                          {amt != null && (
                            <div className="dd-case-amount-col">
                              <span className="dd-case-amount">{p.fmtINR(amt)}</span>
                              <span className="dd-case-amount-lbl">POS</span>
                            </div>
                          )}
                          {isDone && (
                            <button type="button" className="dd-undo-btn"
                              onClick={e => { e.stopPropagation(); p.undoOne(c.id); }}
                              disabled={p.undoingId === c.id}
                              aria-label="Undo dispatch">
                              {p.undoingId === c.id
                                ? <Loader2 size={13} className="ds-spin" />
                                : <Undo2 size={13} />
                              }
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}

                  {p.dispatched.length > 0 && p.activeTab === 'queue' && (
                    <Link to="/app/today" className="dd-cp-view-link">
                      View dispatched list <ArrowUpRight size={12} style={{ marginLeft: 4, display: 'inline' }} />
                    </Link>
                  )}
                </>
              )}
            </motion.div>
          </AnimatePresence>
        </div>
      </div>

      {/* ── Pagination ── */}
      {totalPages > 1 && !p.casesLoading && (
        <Pagination
          currentPage={casePage}
          totalPages={totalPages}
          onPageChange={setCasePage}
          totalElements={totalCases}
          itemLabel="cases"
        />
      )}

      {/* ── Send bar — floats in when cases are selected ── */}
      <AnimatePresence>
        {p.picked.size > 0 && p.canDispatch && (
          <motion.div
            className="dd-send-bar"
            style={{ flexShrink: 0 }}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 8 }}
            transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
          >
            {p.agentObj && (
              <div className="dd-send-bar-agent">
                <div
                  className="dd-send-bar-avatar"
                  style={{ background: hashColor(`${p.agentObj.firstName}${p.agentObj.lastName}`), color: 'var(--text-on-solid)', border: 'none' }}
                >
                  {p.initials(p.agentObj) || <User size={14} />}
                </div>
                <div className="dd-send-bar-agent-info">
                  <span className="dd-send-bar-to">To</span>
                  <span className="dd-send-bar-name">{agentFullName}</span>
                </div>
              </div>
            )}

            <div className="dd-send-bar-info">
              <span className="dd-send-bar-count">{p.picked.size} {p.picked.size === 1 ? 'case' : 'cases'}</span>
              {p.selectedTotal > 0 && (
                <span className="dd-send-bar-value">
                  {p.fmtINR(selectedTotalAnim)}
                </span>
              )}
            </div>

            <div className="dd-send-bar-actions">
              <button type="button" className="dd-bar-btn-cancel"
                onClick={() => p.setPicked(new Set())}
                disabled={p.submitting}>
                Cancel
              </button>
              {p.canOptimize && p.picked.size >= 2 && (
                <button type="button" className="ds-btn is-secondary is-sm"
                  onClick={p.onOptimize}
                  disabled={p.submitting || p.optimizing}
                  title="Order the selected cases by suggested visit priority"
                  aria-label="Optimize visit order">
                  {p.optimizing
                    ? <Loader2 size={13} className="ds-spin" />
                    : <Wand2 size={13} />
                  }
                  {p.optimizedOrder ? 'Re-optimize' : 'Optimize order'}
                </button>
              )}
              <button type="button" className="dd-bar-btn-confirm"
                onClick={() => setShowConfirm(true)}
                disabled={p.submitting}>
                {p.submitting
                  ? <Loader2 size={14} className="ds-spin" />
                  : <><Send size={13} />Dispatch<span className="dd-bar-kbd">{DISPATCH_HINT}</span></>
                }
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {showConfirm && (
        <Modal title="Confirm dispatch"
          subtitle={`Send ${p.picked.size} ${p.picked.size === 1 ? 'case' : 'cases'} to ${agentFullName}?`}
          onClose={() => setShowConfirm(false)}>
          <div className="ps-section-row" style={{ padding: '4px 0 16px', justifyContent: 'flex-start', gap: 24 }}>
            <div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-placeholder)', marginBottom: 4 }}>Officer</div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ink-primary)' }}>{agentFullName || '—'}</div>
            </div>
            <div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-placeholder)', marginBottom: 4 }}>Cases</div>
              <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ink-primary)' }}>{p.picked.size}</div>
            </div>
            {p.selectedTotal > 0 && (
              <div>
                <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-placeholder)', marginBottom: 4 }}>Total</div>
                <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--ink-primary)' }}>{p.fmtINR(p.selectedTotal)}</div>
              </div>
            )}
          </div>
          <ModalFooter onClose={() => setShowConfirm(false)} submitting={p.submitting}
            onSubmit={confirmSend} label="Dispatch" />
        </Modal>
      )}
    </div>
  );
}
