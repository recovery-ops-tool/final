import { useCallback, useEffect, useState } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import {
  FileText, Send, CheckCircle2, Archive, AlertCircle, X, Loader2,
  MessageSquareText, Clock, RefreshCw,
} from 'lucide-react';
import { messageTemplatesApi } from '../api/messageTemplatesApi';
import { extractApiError } from '../utils/extractApiError';
import type { MessageTemplate, MessageTemplateChannel, MessageTemplateStatus } from '../types';
import '../styles/AppPage.css';
import './Dashboard.css';

const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.05, delayChildren: 0.04 } },
};
const fadeUp: Variants = {
  hidden: { opacity: 0, y: 16 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.40, ease: [0.22, 1, 0.36, 1] as [number, number, number, number] } },
};

type ActionKey = 'submit-for-dlt' | 'activate' | 'retire';

const STATUS_TONE: Record<string, string> = {
  ACTIVE: 'is-success', DRAFT: 'is-neutral', PENDING_DLT: 'is-warn', RETIRED: 'is-danger', INACTIVE: 'is-neutral',
};

const STATUSES: MessageTemplateStatus[] = ['DRAFT', 'PENDING_DLT', 'ACTIVE', 'RETIRED', 'INACTIVE'];
const CHANNELS: MessageTemplateChannel[] = ['SMS', 'WHATSAPP', 'RCS', 'EMAIL', 'VOICE_IVR', 'VOICE_AGENT'];

interface HistoryEntry {
  action: ActionKey;
  at: string;
  template: MessageTemplate;
}

export default function MessageTemplatesPage() {
  const [templates, setTemplates] = useState<MessageTemplate[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError]     = useState<string | null>(null);
  const [statusFilter, setStatusFilter]   = useState<MessageTemplateStatus | ''>('');
  const [channelFilter, setChannelFilter] = useState<MessageTemplateChannel | ''>('');

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError]           = useState<string | null>(null);
  const [pending, setPending]       = useState<ActionKey | null>(null);
  const [history, setHistory]       = useState<HistoryEntry[]>([]);

  const selected = templates.find(t => t.id === selectedId) ?? null;

  const loadTemplates = useCallback(() => {
    setListLoading(true); setListError(null);
    messageTemplatesApi.list({
      status: statusFilter || undefined,
      channel: channelFilter || undefined,
      size: 50,
    })
      .then(r => setTemplates(r.content))
      .catch(e => setListError(extractApiError(e, 'Failed to load templates.')))
      .finally(() => setListLoading(false));
  }, [statusFilter, channelFilter]);

  useEffect(() => { loadTemplates(); }, [loadTemplates]);

  const runAction = async (action: ActionKey) => {
    if (!selectedId) return;
    setPending(action); setError(null);
    try {
      const tpl = action === 'submit-for-dlt' ? await messageTemplatesApi.submitForDlt(selectedId)
        : action === 'activate' ? await messageTemplatesApi.activate(selectedId)
        : await messageTemplatesApi.retire(selectedId);
      setHistory(h => [{ action, at: new Date().toISOString(), template: tpl }, ...h].slice(0, 20));
      setTemplates(prev => prev.map(t => t.id === tpl.id ? tpl : t));
    } catch (e) {
      setError(extractApiError(e, 'Action failed.'));
    } finally { setPending(null); }
  };

  return (
    <div className="db-root">
      <AnimatePresence>
        {error && (
          <motion.div key="toast-err" className="db-error-banner" role="alert" style={{ marginBottom: 24 }}
            initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.22 }}>
            <AlertCircle size={16} aria-hidden="true" className="db-error-icon" />
            <div className="db-error-body"><span className="db-error-title">{error}</span></div>
            <button className="db-error-retry" onClick={() => setError(null)} aria-label="Dismiss"><X size={14} aria-hidden="true" /></button>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="db-content">
        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show" style={{ maxWidth: 1100, margin: '0 auto' }}>

          <div className="db-page-header" style={{ marginBottom: 16 }}>
            <div className="db-page-header-left">
              <span className="dd-page-context" style={{ padding: 0 }}>
                {!listLoading ? (
                  <>You have <strong>{templates.length} message templates</strong> configured for custom notifications.</>
                ) : (
                  'Maker-checker review queue'
                )}
              </span>
            </div>
            <div className="db-list-page-actions">
              <select className="ds-input" value={statusFilter} onChange={e => setStatusFilter(e.target.value as MessageTemplateStatus | '')} style={{ margin: 0 }}>
                <option value="">All statuses</option>
                {STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
              </select>
              <select className="ds-input" value={channelFilter} onChange={e => setChannelFilter(e.target.value as MessageTemplateChannel | '')} style={{ margin: 0 }}>
                <option value="">All channels</option>
                {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
              <div className="db-list-btn-group">
                <button type="button" className="ds-btn is-secondary" onClick={loadTemplates} disabled={listLoading} aria-label="Refresh">
                  {listLoading ? <Loader2 size={14} className="ds-spin" /> : <RefreshCw size={14} />} Refresh
                </button>
              </div>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(280px, 1fr) 2fr', gap: 16, alignItems: 'start' }}>
            <motion.section variants={fadeUp} className="ds-card db-card">
              <header className="db-card-head" style={{ borderBottom: '1px solid var(--border-subtle)', padding: '16px 20px', display: 'flex', alignItems: 'center', gap: 8 }}>
                <FileText size={15} style={{ color: 'var(--ink-tertiary)' }} />
                <h2 className="db-card-title">Templates</h2>
              </header>
              <div style={{ maxHeight: 520, overflowY: 'auto' }}>
                {listLoading ? (
                  <div style={{ padding: 20, display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ink-tertiary)', fontSize: 13 }}>
                    <Loader2 size={13} className="ds-spin" /> Loading…
                  </div>
                ) : listError ? (
                  <div style={{ padding: 20, fontSize: 13, color: 'var(--danger)' }}>{listError}</div>
                ) : templates.length === 0 ? (
                  <div style={{ padding: 20, fontSize: 13, color: 'var(--ink-tertiary)', fontStyle: 'italic' }}>No templates match this filter.</div>
                ) : (
                  templates.map(t => (
                    <button key={t.id} type="button" onClick={() => setSelectedId(t.id)}
                      style={{
                        display: 'flex', flexDirection: 'column', gap: 4, width: '100%', textAlign: 'left',
                        padding: '12px 20px', border: 'none', borderBottom: '1px solid var(--border-subtle)',
                        background: t.id === selectedId ? 'var(--bg-subtle)' : 'transparent', cursor: 'pointer',
                      }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, color: 'var(--ink-primary)' }}>{t.templateKey}</span>
                        <span className={`ds-pill ${STATUS_TONE[t.status] ?? 'is-neutral'}`} style={{ fontSize: 10 }}>{t.status}</span>
                      </div>
                      <span style={{ fontSize: 11.5, color: 'var(--ink-tertiary)' }}>{t.channel} · v{t.version} · {t.language}</span>
                    </button>
                  ))
                )}
              </div>
            </motion.section>

            <AnimatePresence mode="wait">
              {selected ? (
                <motion.section key={selected.id} variants={fadeUp} initial="hidden" animate="show" exit={{ opacity: 0 }} className="ds-card db-card">
                  <header className="db-card-head" style={{ borderBottom: '1px solid var(--border-subtle)', padding: '16px 20px', display: 'flex', alignItems: 'center', gap: 8 }}>
                    <MessageSquareText size={15} style={{ color: 'var(--ink-tertiary)' }} />
                    <h2 className="db-card-title">{selected.templateKey}</h2>
                    <span className={`ds-pill ${STATUS_TONE[selected.status] ?? 'is-neutral'}`} style={{ marginLeft: 'auto' }}>{selected.status}</span>
                  </header>
                  <div className="db-card-body" style={{ padding: 20, display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 16, fontSize: 13 }}>
                    <Field label="Version" value={selected.version} mono />
                    <Field label="Channel" value={selected.channel} />
                    <Field label="Language" value={selected.language} />
                    {selected.category && <Field label="Category" value={selected.category} />}
                    {selected.dltTemplateId && <Field label="DLT template ID" value={selected.dltTemplateId} mono />}
                    {selected.whatsappNamespace && <Field label="WhatsApp namespace" value={selected.whatsappNamespace} mono />}
                    {selected.approvedByUserId && <Field label="Approved by" value={selected.approvedByUserId} mono />}
                    {selected.approvedAt && <Field label="Approved at" value={new Date(selected.approvedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })} />}
                    {selected.subject && <Field label="Subject" value={selected.subject} span2 />}
                    <Field label="Body" value={selected.body} span2 />
                  </div>
                  <div style={{ padding: '0 20px 20px', display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                    <button type="button" className="ds-btn is-secondary" disabled={pending !== null}
                      onClick={() => runAction('submit-for-dlt')}>
                      {pending === 'submit-for-dlt' ? <Loader2 size={14} className="ds-spin" /> : <Send size={14} />}
                      Submit for DLT
                    </button>
                    <button type="button" className="ds-btn is-primary" disabled={pending !== null}
                      onClick={() => runAction('activate')}>
                      {pending === 'activate' ? <Loader2 size={14} className="ds-spin" /> : <CheckCircle2 size={14} />}
                      Activate
                    </button>
                    <button type="button" className="ds-btn is-secondary" disabled={pending !== null}
                      onClick={() => runAction('retire')} style={{ color: 'var(--danger)' }}>
                      {pending === 'retire' ? <Loader2 size={14} className="ds-spin" /> : <Archive size={14} />}
                      Retire
                    </button>
                  </div>
                  <p style={{ margin: '0 20px 20px', fontSize: 11.5, color: 'var(--ink-tertiary)' }}>
                    Submit for DLT: DRAFT → PENDING_DLT. Activate: DRAFT/PENDING_DLT → ACTIVE (requires a different
                    user than the template's creator, and channel-specific fields — DLT id for SMS, WhatsApp
                    namespace, or subject for EMAIL — plus a no-harassment content lint). Retire: any status →
                    RETIRED, idempotent.
                  </p>
                </motion.section>
              ) : (
                <motion.section variants={fadeUp} className="ds-card db-card" style={{ padding: 40, textAlign: 'center', color: 'var(--ink-tertiary)', fontSize: 13 }}>
                  Select a template from the list to review it.
                </motion.section>
              )}
            </AnimatePresence>
          </div>

          {history.length > 0 && (
            <motion.section variants={fadeUp} className="ds-card db-card" style={{ marginTop: 16 }}>
              <header className="db-card-head" style={{ borderBottom: '1px solid var(--border-subtle)', padding: '16px 20px', display: 'flex', alignItems: 'center', gap: 8 }}>
                <Clock size={15} style={{ color: 'var(--ink-tertiary)' }} />
                <h2 className="db-card-title">This session's actions</h2>
              </header>
              <div className="ds-table-wrap" style={{ border: 'none' }}>
                <table className="ds-table">
                  <thead>
                    <tr style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                      <th style={{ padding: '12px 16px', paddingLeft: 24 }}>When</th>
                      <th style={{ padding: '12px 16px' }}>Action</th>
                      <th style={{ padding: '12px 16px' }}>Template key</th>
                      <th style={{ padding: '12px 16px' }}>Resulting status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.map((h, i) => (
                      <tr key={i} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                        <td style={{ padding: '12px 16px', paddingLeft: 24, color: 'var(--ink-tertiary)', fontSize: 12 }}>{new Date(h.at).toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata' })}</td>
                        <td style={{ padding: '12px 16px' }}>{h.action}</td>
                        <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)' }}>{h.template.templateKey}</td>
                        <td style={{ padding: '12px 16px' }}><span className={`ds-pill ${STATUS_TONE[h.template.status] ?? 'is-neutral'}`}>{h.template.status}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </motion.section>
          )}
        </motion.div>
      </div>
    </div>
  );
}

function Field({ label, value, mono, span2 }: { label: string; value: string; mono?: boolean; span2?: boolean }) {
  return (
    <div style={{ gridColumn: span2 ? '1 / -1' : undefined }}>
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--ink-tertiary)', textTransform: 'uppercase', letterSpacing: '0.04em', marginBottom: 4 }}>{label}</div>
      <div style={{ color: 'var(--ink-primary)', fontFamily: mono ? 'var(--font-mono)' : undefined, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{value}</div>
    </div>
  );
}
