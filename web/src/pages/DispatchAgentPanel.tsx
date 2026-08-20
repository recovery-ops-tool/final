import { useEffect, useState, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { CheckCircle2, Loader2, Users, UserCheck, Search, X, User } from 'lucide-react';
import type { UserResponse, AllocationResponse } from '../types';
import { hashColor } from '../utils/navConfig';
import { DonutCard } from './DashboardShared';

interface Props {
  agents: UserResponse[];
  agentsLoading: boolean;
  selectedAgent: string;
  agentObj: UserResponse | undefined;
  agentFullName: string;
  setAgent: (id: string) => void;
  cases: AllocationResponse[];
  dispatched: AllocationResponse[];
  undispatchedCases: AllocationResponse[];
  casesLoading: boolean;
  dispatchPct: number;
  initials: (a: UserResponse) => string;
  dispatchDayLabel: string;
}

// ── Count-up animation ────────────────────────────────────────────────────────

function useCountUp(target: number, duration = 700) {
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

// ── Stats footer ──────────────────────────────────────────────────────────────

function AgentStats({ cases, dispatched, dispatchPct, casesLoading }: {
  cases: AllocationResponse[];
  dispatched: AllocationResponse[];
  dispatchPct: number;
  casesLoading: boolean;
}) {
  useCountUp(dispatched.length);

  if (casesLoading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Loader2 size={14} className="ds-spin" style={{ color: 'var(--ink-tertiary)' }} />
        <span className="ds-skel" style={{ height: 12, flex: 1 }} />
      </div>
    );
  }

  const fmtC = (n: number) =>
    n >= 1e7 ? `₹${(n/1e7).toFixed(1)}Cr` :
    n >= 1e5 ? `₹${(n/1e5).toFixed(1)}L`  :
    n >= 1e3 ? `₹${(n/1e3).toFixed(0)}K`  : `₹${Math.round(n)}`;

  const resolveAmt = (c: AllocationResponse) => {
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
    return 0;
  };

  const totalPortfolio = cases.reduce((s, c) => s + resolveAmt(c), 0);

  if (cases.length === 0) {
    return (
      <p style={{ fontSize: 12, color: 'var(--ink-tertiary)', textAlign: 'center', margin: 0 }}>
        No cases assigned today
      </p>
    );
  }

  // Same donut + legend treatment as the dashboard's "Case assignment" card,
  // so dispatch progress reads identically to the org-level chart.
  const slices = [
    { label: 'Sent', value: dispatched.length,                  color: 'var(--dbc-1)' },
    { label: 'Left', value: cases.length - dispatched.length,   color: '#D4EBE2' },
  ];

  return (
    <div className="dd-dispatch-ring-wrap">
      <DonutCard slices={slices} centerLabel="CASES" size={118} />
      <div className="dd-ap-stats">
        <div className="dd-ap-stat">
          <span className="dd-ap-stat-num" style={{ fontSize: 13, letterSpacing: '-0.01em' }}>{fmtC(totalPortfolio)}</span>
          <span className="dd-ap-stat-lbl">Portfolio</span>
        </div>
      </div>
    </div>
  );
}

// ── Agent Panel ───────────────────────────────────────────────────────────────

export default function DispatchAgentPanel(p: Props) {
  const [isSearchActive, setIsSearchActive] = useState(false);
  const [agentSearch, setAgentSearch] = useState('');

  const filteredAgents = useMemo(() => {
    const q = agentSearch.trim().toLowerCase();
    if (!q) return p.agents;
    return p.agents.filter(a =>
      `${a.firstName} ${a.lastName}`.toLowerCase().includes(q) || a.email?.toLowerCase().includes(q)
    );
  }, [p.agents, agentSearch]);

  return (
    <div className="dd-officers-card ds-card is-overflow-hidden">
      <div className="dd-ap-header db-card-head">
        {!isSearchActive ? (
          <>
            <h2 className="dd-ap-header-label db-list-title">
              <Users size={12} aria-hidden="true" />
              Field Officers
            </h2>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginLeft: 'auto' }}>
              {!p.agentsLoading && (
                <span className="dd-cp-tab-count">{p.agents.length}</span>
              )}
              <button type="button" className="dd-ap-header-search-btn"
                onClick={() => setIsSearchActive(true)} aria-label="Search field officers">
                <Search size={13} />
              </button>
            </div>
          </>
        ) : (
          <div className="dd-agent-search-active">
            <Search size={13} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
            <input
              autoFocus
              value={agentSearch}
              onChange={e => setAgentSearch(e.target.value)}
              placeholder="Search officers…"
            />
            <button type="button" className="dd-agent-search-close"
              onClick={() => { setIsSearchActive(false); setAgentSearch(''); }} aria-label="Close search">
              <X size={13} />
            </button>
          </div>
        )}
      </div>

      <div className="dd-agent-list">
        {p.agentsLoading ? (
          Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="dd-agent-skel" style={{ opacity: 1 - i * 0.12 }}>
              <span className="ds-skel" style={{ width: 32, height: 32, borderRadius: '50%', flexShrink: 0 }} />
              <span className="ds-skel" style={{ height: 13, flex: 1 }} />
            </div>
          ))
        ) : filteredAgents.length === 0 ? (
          <div style={{ padding: '56px 24px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
            <span className="ds-empty-icon">
              <UserCheck size={20} aria-hidden="true" />
            </span>
            <span className="dd-cp-empty-title">{agentSearch ? 'No officers match' : 'No field officers'}</span>
            <p className="dd-cp-empty-sub">
              {agentSearch ? 'Try a different search term.' : 'No field officers are assigned to your organization yet.'}
            </p>
          </div>
        ) : (
          filteredAgents.map((a) => {
            const isActive = p.selectedAgent === a.id;
            return (
              <button
                key={a.id}
                type="button"
                className={`dd-agent-row${isActive ? ' is-active' : ''}`}
                onClick={() => p.setAgent(a.id)}
              >
                <span
                  className="dd-fo-avatar"
                  style={{ background: hashColor(`${a.firstName}${a.lastName}`), color: 'var(--text-on-solid)', border: 'none' }}
                >
                  {p.initials(a) || <User size={14} />}
                </span>
                <span className="dd-agent-row-name">
                  {`${a.firstName} ${a.lastName}`.trim()}
                </span>
                {isActive && (
                  <CheckCircle2 size={14} className="dd-agent-row-check" aria-hidden="true" />
                )}
              </button>
            );
          })
        )}
      </div>

      <AnimatePresence mode="wait">
        {p.agentObj && (
          <motion.div
            key={p.agentObj.id}
            className="dd-agent-stats-footer"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.22 }}
          >
            <AgentStats
              cases={p.cases}
              dispatched={p.dispatched}
              dispatchPct={p.dispatchPct}
              casesLoading={p.casesLoading}
            />
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
