import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { nonContactablesApi, type NonContactableResponse } from '../api/nonContactablesApi';
import { usePermissions } from '../hooks/usePermissions';
import {
  PhoneOff, RefreshCw, Search, X, Plus,
  AlertCircle, CheckCircle2, Loader2, Trash2,
} from 'lucide-react';
import { Pagination } from '../components/Pagination';
import '../styles/AppPage.css';
import './Dashboard.css';

const PAGE_SIZE = 20;

const fmtDate = (s?: string | null) =>
  s ? new Date(s).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

const shortId = (id?: string | null) => (id ? `${id.slice(0, 8)}…` : '—');

// ── Create modal ─────────────────────────────────────────────────────────────

function CreateNonContactableModal({ onClose, onSuccess }: { onClose: () => void; onSuccess: () => void }) {
  const [allocationId, setAllocationId] = useState('');
  const [visitId, setVisitId]           = useState('');
  const [reason, setReason]             = useState('');
  const [notes, setNotes]               = useState('');
  const [loading, setLoading]           = useState(false);
  const [error, setError]               = useState<string | null>(null);

  const canSubmit = allocationId.trim().length > 0 && reason.trim().length > 0;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setLoading(true); setError(null);
    try {
      await nonContactablesApi.create({
        allocationId: allocationId.trim(),
        visitId: visitId.trim() || undefined,
        reason: reason.trim(),
        notes: notes.trim() || undefined,
      });
      onSuccess();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to record non-contactable outcome.');
      setLoading(false);
    }
  };

  return (
    <div className="ds-modal-overlay" onClick={onClose}>
      <div className="ds-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="ds-modal-header">
          <span className="ds-modal-title">Record non-contactable</span>
          <button type="button" onClick={onClose} className="ds-modal-close" aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="ds-modal-body">
          <div className="ds-field">
            <span className="ds-label is-required">Allocation ID</span>
            <input
              type="text"
              value={allocationId}
              onChange={(e) => setAllocationId(e.target.value)}
              placeholder="UUID — from the Allocations list"
              className="ds-input"
              style={{ fontFamily: 'var(--mono)', fontSize: 12 }}
              autoFocus
            />
          </div>

          <div className="ds-field">
            <span className="ds-label">Visit ID</span>
            <input
              type="text"
              value={visitId}
              onChange={(e) => setVisitId(e.target.value)}
              placeholder="Optional — UUID of the linked visit log"
              className="ds-input"
              style={{ fontFamily: 'var(--mono)', fontSize: 12 }}
            />
          </div>

          <div className="ds-field">
            <span className="ds-label is-required">Reason</span>
            <input
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Absent, Wrong address, Refused, Moved"
              className="ds-input"
              maxLength={50}
            />
          </div>

          <div className="ds-field">
            <span className="ds-label">Notes</span>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              placeholder="Additional detail from the visit…"
              className="ds-textarea"
              maxLength={2000}
            />
          </div>

          {error && (
            <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderRadius: 8, background: 'var(--danger-subtle)', border: '1px solid color-mix(in srgb, var(--danger) 30%, transparent)', color: 'var(--danger)', fontSize: 12.5 }}>
              <AlertCircle size={14} /><span>{error}</span>
            </div>
          )}
        </div>

        <div className="ds-modal-actions">
          <button type="button" onClick={onClose} className="ds-btn is-secondary">
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit || loading}
            className="ds-btn is-primary"
          >
            {loading ? <Loader2 size={14} className="ds-spin" /> : null}
            {loading ? 'Saving…' : 'Record outcome'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function NonContactablesPage() {
  const { hasPermission, hasAnyRole } = usePermissions();
  const canCreate = hasPermission('NON_CONTACTABLE_CREATE');
  const canDelete = hasAnyRole('PLATFORM_ADMIN', 'ORG_ADMIN');

  const [records, setRecords]             = useState<NonContactableResponse[]>([]);
  const [loading, setLoading]             = useState(true);
  const [page, setPage]                   = useState(0);
  const [totalPages, setTotalPages]       = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [searchInput, setSearchInput]     = useState('');
  const [searchOpen, setSearchOpen]       = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [deletingId, setDeletingId]       = useState<string | null>(null);
  const [toast, setToast]                 = useState<{ kind: 'ok' | 'err'; msg: string } | null>(null);

  const inputRef = useRef<HTMLInputElement>(null);

  const fetchRecords = useCallback(async () => {
    setLoading(true);
    try {
      const data = await nonContactablesApi.list(page, PAGE_SIZE);
      setRecords(data.content ?? []);
      setTotalPages(data.totalPages ?? 0);
      setTotalElements(data.totalElements ?? 0);
    } catch {
      // silent — empty state handles
    } finally { setLoading(false); }
  }, [page]);

  useEffect(() => { fetchRecords(); }, [fetchRecords]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 4000);
    return () => clearTimeout(t);
  }, [toast]);

  const visibleRecords = useMemo(() => {
    const q = searchInput.trim().toLowerCase();
    if (!q) return records;
    return records.filter(r => {
      const fields = [r.allocationId, r.reason, r.notes, r.agentId];
      return fields.some(f => typeof f === 'string' && f.toLowerCase().includes(q));
    });
  }, [records, searchInput]);

  const handleDelete = async (id: string) => {
    setDeletingId(id);
    try {
      await nonContactablesApi.remove(id);
      setToast({ kind: 'ok', msg: 'Record deleted.' });
      fetchRecords();
    } catch (e: any) {
      setToast({ kind: 'err', msg: e?.response?.data?.message || 'Failed to delete record.' });
    } finally { setDeletingId(null); }
  };

  return (
    <div className="db-root db-fill-root" style={{ height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <div className="db-content" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', flex: 1, paddingBottom: 36 }}>
        <div className="db-page-header">
          <div className="db-page-header-left">
            {!loading && (
              <p className="dd-page-context">
                You have <strong>{totalElements.toLocaleString('en-IN')} non-contactable records</strong> logged by field officers.
              </p>
            )}
          </div>
          <div className="db-list-page-actions">
            <div className="db-list-btn-group">
              <button type="button" onClick={fetchRecords} disabled={loading} className="ds-btn is-secondary" aria-label="Refresh" title="Refresh">
                <RefreshCw size={14} className={loading ? 'ds-spin' : ''} /> Refresh
              </button>
              {canCreate && (
                <button type="button" onClick={() => setShowCreateModal(true)} className="ds-btn is-primary">
                  <Plus size={14} /> Record outcome
                </button>
              )}
            </div>
          </div>
        </div>

        <div className="db-inner" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <AnimatePresence>
            {toast && (
              <motion.div
                key="toast" className={`ds-toast ${toast.kind === 'ok' ? 'is-ok' : 'is-err'}`} role="status"
                initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.22 }}
                onClick={() => setToast(null)} style={{ marginBottom: 16 }}
              >
                {toast.kind === 'ok' ? <CheckCircle2 size={14} /> : <AlertCircle size={14} />}
                <span>{toast.msg}</span>
                <X size={13} className="ds-toast-x" />
              </motion.div>
            )}
          </AnimatePresence>

          <section className="ds-card db-card is-list-card" style={{ marginTop: 0, display: 'flex', flexDirection: 'column', ...(visibleRecords.length > 0 ? { flex: 1, minHeight: 0 } : {}) }}>
            <header className="db-card-head db-list-head" style={{ borderBottom: 'none', gap: 12 }}>
              <h3 className="db-list-title">Skips</h3>
              <div className="db-list-head-actions">
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
                          setSearchInput('');
                        }
                      }}
                    >
                      <Search size={14} style={{ color: 'var(--ink-tertiary)', flexShrink: 0 }} />
                      <input
                        ref={inputRef}
                        autoFocus
                        type="search"
                        value={searchInput}
                        onChange={e => setSearchInput(e.target.value)}
                        placeholder="Search allocation, reason, notes…"
                        autoComplete="off"
                        spellCheck={false}
                        aria-label="Search non-contactable records on this page"
                        style={{ border: 'none', background: 'transparent', outline: 'none', fontSize: 13, width: '100%', paddingLeft: 8 }}
                      />
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            </header>

            <div className="db-list-body db-list-scroll">
              {loading ? (
                <div className="db-list-skel-wrap">
                  {Array.from({ length: 6 }).map((_, i) => (
                    <div key={i} className="dd-case-skel db-list-skel" style={{ opacity: 1 - i * 0.09 }}>
                      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
                        <span className="ds-skel" style={{ height: 16, width: '40%' }} />
                        <span className="ds-skel" style={{ height: 12, width: '25%' }} />
                      </div>
                      <span className="ds-skel" style={{ height: 18, width: 80 }} />
                    </div>
                  ))}
                </div>
              ) : visibleRecords.length === 0 ? (
                <motion.div className="ds-empty" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.3 }}>

                  <PhoneOff size={32} className="ds-empty-icon" />
                  <span className="ds-empty-title">{searchInput ? 'No matches on this page' : 'No non-contactable records'}</span>
                  <span className="ds-empty-sub">
                    {searchInput
                      ? 'Nothing on the current page matches your search. Clear the search, or change page.'
                      : 'Records will appear here once field officers log a non-contactable visit outcome.'}
                  </span>
                </motion.div>
              ) : (
                <motion.div>
                  {visibleRecords.map((r, idx) => (
                    <motion.div
                      key={r.id}
                      className="db-att-row is-list-row"
                      style={{ cursor: 'default' }}
                      initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, delay: idx * 0.03 }}
                    >
                      <div className="db-list-row-main">
                        <span className="db-att-label">
                          <PhoneOff size={13} style={{ color: 'var(--ink-tertiary)' }} />
                          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5 }} title={r.allocationId}>{shortId(r.allocationId)}</span>
                          <span className="ds-pill is-warn">{r.reason}</span>
                        </span>
                        <div className="db-list-row-meta" style={{ padding: 0, flexWrap: 'wrap' }}>
                          {r.notes && (
                            <span className="db-kpi2-foot-meta" style={{ fontSize: 11, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {r.notes}
                            </span>
                          )}
                          <span className="db-kpi2-foot-meta" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }} title={r.agentId}>
                            / Agent {shortId(r.agentId)}
                          </span>
                          <span className="db-kpi2-foot-meta" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                            / {fmtDate(r.createdAt)}
                          </span>
                        </div>
                      </div>

                      {canDelete && (
                        <div className="db-list-row-right" style={{ flexShrink: 0 }}>
                          <button
                            type="button"
                            onClick={() => handleDelete(r.id)}
                            disabled={deletingId === r.id}
                            className="db-error-retry"
                            style={{ background: 'var(--danger-subtle)', color: 'var(--danger)', padding: '0 8px', height: 26, fontSize: 11, fontWeight: 600, width: 'auto' }}
                            title="Delete record"
                            aria-label="Delete record"
                          >
                            {deletingId === r.id ? <Loader2 size={12} className="ds-spin" /> : <Trash2 size={12} />}
                          </button>
                        </div>
                      )}
                    </motion.div>
                  ))}
                </motion.div>
              )}
            </div>

            {!loading && records.length > 0 && totalPages > 1 && (
              <Pagination currentPage={page} totalPages={totalPages} onPageChange={setPage} totalElements={totalElements} itemLabel="records" />
            )}
          </section>
        </div>
      </div>

      <AnimatePresence>
        {showCreateModal && (
          <CreateNonContactableModal
            onClose={() => setShowCreateModal(false)}
            onSuccess={() => { setShowCreateModal(false); setToast({ kind: 'ok', msg: 'Non-contactable outcome recorded.' }); fetchRecords(); }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}
