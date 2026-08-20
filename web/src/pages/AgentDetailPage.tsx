import { useState, useEffect, useRef, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import axiosInstance from '../api/axiosInstance';
import { useAuth } from '../AuthContext';
import {
  ArrowLeft, Mail, Calendar, CheckCircle2, Clock,
  Target, Activity, AlertCircle, IndianRupee, Briefcase, MapPin, X
} from 'lucide-react';
import type { UserResponse, VisitLogResponse } from '../types';
import { type AgentPerf, type CollectionReport, DispPill } from './AgentDetailHelpers';
import { hashColor } from '../utils/navConfig';
import '../styles/AppPage.css';
import './Dashboard.css';

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

function KpiCard({ label, value, footer, icon: Icon, accent, warn, onClick }: {
  label: string; value: string | number;
  footer?: React.ReactNode;
  icon?: React.ElementType; accent?: boolean; warn?: boolean;
  onClick?: () => void;
}) {
  const { ref, fire } = useRipple<HTMLButtonElement>();
  return (
    <motion.button ref={ref} type="button" variants={fadeUp}
      className={`db-kpi2-card${accent ? ' is-accent' : ''}${warn ? ' is-warn' : ''}${onClick ? ' is-hoverable' : ''}`}
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

const fmtNum = (v?: number | null) => v != null ? v.toLocaleString('en-IN') : '—';
const inr = (n?: number | null) => n != null && Number(n) > 0 ? `₹${Number(n).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` : '₹0';

export default function AgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user: currentUser } = useAuth();
  const orgId = currentUser?.organizationId ?? '';

  const [profile, setProfile]         = useState<UserResponse | null>(null);
  const [perf, setPerf]               = useState<AgentPerf | null>(null);
  const [collections, setCollections] = useState<CollectionReport | null>(null);
  const [visits, setVisits]           = useState<VisitLogResponse[]>([]);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState<string | null>(null);

  useEffect(() => {
    if (!id || !orgId) return;
    const load = async () => {
      setLoading(true); setError(null);
      try {
        const todayIso = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });
        const [istYear, istMonth] = todayIso.split('-');
        const from = `${istYear}-${istMonth}-01`;
        const to   = todayIso;
        const [profileRes, teamPerfRes, collRes, visitsRes] = await Promise.allSettled([
          axiosInstance.get(`/api/v1/users/${id}`),
          axiosInstance.get(`/api/v1/reports/team/performance`, { params: { orgId, from, to } }),
          axiosInstance.get(`/api/v1/reports/agents/${id}/collections/summary`),
          axiosInstance.get(`/api/v1/visit-logs/agent/${id}`, { params: { page: 0, size: 10, sort: 'visitDate,desc' } }),
        ]);
        if (profileRes.status === 'fulfilled') setProfile(profileRes.value.data?.data ?? profileRes.value.data);
        else setError('Failed to load agent profile.');
        if (teamPerfRes.status === 'fulfilled') {
          const breakdown: AgentPerf[] = teamPerfRes.value.data?.data?.agentBreakdown ?? [];
          setPerf(breakdown.find((a: AgentPerf & { agentId?: string }) => a.agentId === id) ?? null);
        }
        if (collRes.status === 'fulfilled') setCollections(collRes.value.data?.data ?? null);
        if (visitsRes.status === 'fulfilled') {
          const d = visitsRes.value.data?.data;
          setVisits(Array.isArray(d) ? d : d?.content ?? []);
        }
      } catch { setError('Failed to load agent data.'); }
      finally { setLoading(false); }
    };
    load();
  }, [id, orgId]);

  const assignedAnim  = useCountUp(perf?.totalAssigned ?? 0);
  const visitedAnim   = useCountUp(perf?.totalVisited ?? 0);
  const pendingAnim   = useCountUp(perf?.totalPending ?? 0);

  if (loading) {
    return (
      <div className="db-root">
        <div className="db-content">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
            <button type="button" onClick={() => navigate(-1)} className="ds-btn is-secondary" style={{ padding: '0 8px', height: 32 }} aria-label="Go back">
              <ArrowLeft size={16} />
            </button>
            <h1 className="dd-page-title"><span className="ds-skel" style={{ width: 200, height: 28 }} /></h1>
          </div>
          <div className="db-inner">
            <div className="db-kpi-band">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="db-kpi2-card">
                  <span className="ds-skel" style={{ height: 40, width: '100%' }} />
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (error || !profile) {
    return (
      <div className="db-root">
        <div className="db-content">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
            <button type="button" onClick={() => navigate(-1)} className="ds-btn is-secondary" style={{ padding: '0 8px', height: 32 }} aria-label="Go back">
              <ArrowLeft size={16} />
            </button>
            <h1 className="dd-page-title">Agent Profile</h1>
          </div>
          <div className="db-inner">
            <div className="ds-empty" style={{ padding: '80px 0' }}>
              <AlertCircle size={32} className="ds-empty-icon" style={{ color: 'var(--error)' }} />
              <span className="ds-empty-title">{error || 'Agent not found'}</span>
              <button type="button" onClick={() => navigate(-1)} className="ds-btn is-secondary" style={{ marginTop: 12 }}>
                <ArrowLeft size={14} /> Go back
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const fullName = `${profile.firstName} ${profile.lastName}`.trim();
  const initials = `${profile.firstName?.[0] ?? ''}${profile.lastName?.[0] ?? ''}`.toUpperCase();
  const color = hashColor(fullName);

  return (
    <div className="db-root">
      <div className="db-content">


        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show">
          <div className="db-kpi-band">
            <KpiCard label="Cases Assigned" value={fmtNum(assignedAnim)}
              icon={Briefcase} footer={<span className="db-kpi2-foot-meta">This month</span>} />
            <KpiCard label="Cases Visited" value={fmtNum(visitedAnim)} accent
              icon={CheckCircle2} footer={<span className="db-kpi2-foot-meta">{Number(perf?.visitCompletionRate ?? 0).toFixed(1)}% completion</span>} />
            <KpiCard label="Pending Visits" value={fmtNum(pendingAnim)} warn={(perf?.totalPending ?? 0) > 0}
              icon={Clock} footer={<span className="db-kpi2-foot-meta">Queue remaining</span>} />
            <KpiCard label="Amount Collected" value={inr(perf?.amountCollected)} accent
              icon={IndianRupee} footer={<span className="db-kpi2-foot-meta">{Number(perf?.collectionEfficiency ?? 0).toFixed(1)}% efficiency</span>} />
          </div>

          <div style={{ display: 'flex', gap: 24, alignItems: 'flex-start' }}>
            {/* Split 40: Performance Details */}
            <motion.div variants={fadeUp} className="db-split-40 ds-card db-card" style={{ flex: '0 0 380px' }}>
              <header className="db-card-head" style={{ borderBottom: '1px solid var(--border-subtle)', flexWrap: 'wrap', gap: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <button type="button" onClick={() => navigate(-1)} className="ds-btn is-secondary" style={{ padding: '0 8px', height: 28 }} aria-label="Go back">
                    <ArrowLeft size={14} />
                  </button>
                  <div style={{ width: 24, height: 24, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, background: `${color}20`, border: `1px solid ${color}40`, color }}>
                    {initials || <User size={12} />}
                  </div>
                  <h1 className="dd-page-title">{fullName}</h1>
                  <span className={`ds-pill ${profile.enabled ? 'is-success' : 'is-neutral'}`} style={{ marginLeft: 4 }}>
                    {profile.enabled ? 'Active' : 'Disabled'}
                  </span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginLeft: 'auto', flexWrap: 'wrap' }}>
                  <span className="db-kpi2-foot-meta" style={{ display: 'flex', alignItems: 'center', gap: 4 }}><Mail size={12}/> {profile.email}</span>
                  {profile.createdAt && (
                    <span className="db-kpi2-foot-meta" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                      <Calendar size={12}/> Joined {new Date(profile.createdAt).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', month: 'short', year: 'numeric' })}
                    </span>
                  )}
                  {perf?.rankInOrg != null && (
                    <span className="ds-pill is-accent">Rank #{perf.rankInOrg}</span>
                  )}
                </div>
              </header>
              <div className="db-card-body" style={{ padding: 0 }}>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  {[
                    { label: 'Cases assigned',               value: fmtNum(perf?.totalAssigned) },
                    { label: 'Cases visited',                value: fmtNum(perf?.totalVisited) },
                    { label: 'Collections submitted',        value: fmtNum(perf?.totalCollected) },
                    { label: 'Visit completion rate',        value: `${Number(perf?.visitCompletionRate ?? 0).toFixed(1)}%` },
                    { label: 'Collection efficiency',        value: `${Number(perf?.collectionEfficiency ?? 0).toFixed(1)}%` },
                    { label: 'Efficiency score',             value: Number(perf?.efficiencyScore ?? 0).toFixed(2) },
                    { label: 'Amount collected',             value: inr(perf?.amountCollected) },
                    { label: 'Amount outstanding',           value: inr(perf?.amountOutstanding) },
                    { label: 'Total collected (all-time)',   value: inr(collections?.totalAmountApproved) },
                    { label: 'Total submissions (all-time)', value: fmtNum(collections?.totalSubmissions) },
                  ].map((row, i) => (
                    <div key={row.label} style={{ display: 'flex', justifyContent: 'space-between', padding: '12px 20px', borderBottom: i < 9 ? '1px solid var(--border-subtle)' : 'none' }}>
                      <span className="db-att-label">{row.label}</span>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 13, fontWeight: 600, color: 'var(--ink-primary)' }}>{row.value}</span>
                    </div>
                  ))}
                </div>
              </div>
            </motion.div>

            {/* Split 60: Recent Visits Table */}
            <motion.div variants={fadeUp} className="db-split-60 ds-card db-card" style={{ flex: 1 }}>
              <header className="db-card-head" style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                <h2 className="db-card-title">Recent Visits</h2>
                <button type="button" onClick={() => navigate(`/app/visits?agentId=${id}`)} className="db-customize-btn">
                  View all
                </button>
              </header>
              <div className="ds-table-wrap" style={{ border: 'none', borderRadius: 0 }}>
                <table className="ds-table">
                  <thead>
                    <tr style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                      <th style={{ padding: '12px 16px', paddingLeft: 24 }}>Loan / Address</th>
                      <th style={{ padding: '12px 16px' }}>Status</th>
                      <th className="is-right" style={{ padding: '12px 16px', paddingRight: 24 }}>Date</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visits.length === 0 ? (
                      <tr>
                        <td colSpan={3}>
                          <div className="ds-empty" style={{ padding: '60px 0' }}>
                            <Activity size={24} className="ds-empty-icon" />
                            <span className="ds-empty-title">No visits recorded yet</span>
                          </div>
                        </td>
                      </tr>
                    ) : visits.map((v) => (
                      <tr key={v.id} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                        <td style={{ padding: '12px 16px', paddingLeft: 24 }}>
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                            <span style={{ fontFamily: 'var(--font-mono)', fontSize: 13, fontWeight: 600, color: 'var(--ink-primary)' }}>
                              {(v as any).loanNumber ?? '—'}
                            </span>
                            <span style={{ fontSize: 12, color: 'var(--ink-tertiary)' }}>
                              <MapPin size={10} style={{ display: 'inline', marginRight: 4 }} />
                              {v.gpsAddress ?? 'No address recorded'}
                            </span>
                          </div>
                        </td>
                        <td style={{ padding: '12px 16px' }}>
                          <DispPill status={v.disp} />
                        </td>
                        <td className="is-right" style={{ padding: '12px 16px', paddingRight: 24, fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--ink-secondary)' }}>
                          {v.visitDate ? new Date(v.visitDate).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </motion.div>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
