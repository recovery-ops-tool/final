import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { AlertCircle, CheckCircle2, X, Plus, SquarePen, Trash2, Loader2 } from 'lucide-react';
import { systemPromptApi } from '../api/systemPromptApi';
import type { SystemPromptResponse } from '../api/systemPromptApi';
import { PageFab } from '../components/PageFab';
import { Modal, ModalFooter, FormSection, Input } from './PlatformSetupShared';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';

const SUGGESTED_KEYS = ['lucien.system', 'lucien.coach', 'lucien.summarise', 'lucien.classify'];

// ── Motion variants ────────────────────────────────────────────────────────────

const stagger = {
  hidden: {},
  show: { transition: { staggerChildren: 0.05, delayChildren: 0.04 } },
};

const fadeUp: Variants = {
  hidden: { opacity: 0, y: 16 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.40, ease: [0.22, 1, 0.36, 1] as [number, number, number, number] } },
};

const MIN_PROMPT_LEN = 50;
const MAX_PROMPT_LEN = 10000;
const MAX_DESC_LEN = 500;

type RowStatus = 'loading' | 'found' | 'not-found' | 'error';
interface RowState {
  status: RowStatus;
  data?: SystemPromptResponse;
  error?: string;
}

const fmtDate = (s?: string | null) =>
  s ? new Date(s).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: '2-digit' }) : '—';

export default function SystemPromptAdminPage({ headerExtra }: { headerExtra?: ReactNode }) {
  const [customKeys, setCustomKeys] = useState<string[]>([]);
  const keys = useMemo(() => Array.from(new Set([...SUGGESTED_KEYS, ...customKeys])), [customKeys]);
  const [rows, setRows] = useState<Record<string, RowState>>({});

  const [showAdd, setShowAdd]           = useState(false);
  const [newKey, setNewKey]             = useState('');
  const [newTemplate, setNewTemplate]   = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [addSaving, setAddSaving]       = useState(false);
  const [addError, setAddError]         = useState<string | null>(null);

  const [editingKey, setEditingKey]     = useState<string | null>(null);
  const [editTemplate, setEditTemplate] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [saving, setSaving]             = useState(false);
  const [modalError, setModalError]     = useState<string | null>(null);

  const [savedKey, setSavedKey]         = useState<string | null>(null);
  const [deletingKey, setDeletingKey]   = useState<string | null>(null);

  const loadAll = useCallback(() => {
    setRows(prev => {
      const next = { ...prev };
      keys.forEach(k => { next[k] = { status: 'loading' }; });
      return next;
    });
    keys.forEach(async (k) => {
      try {
        const data = await systemPromptApi.get(k);
        setRows(prev => ({ ...prev, [k]: { status: 'found', data } }));
      } catch (e: any) {
        if (e?.response?.status === 404) setRows(prev => ({ ...prev, [k]: { status: 'not-found' } }));
        else setRows(prev => ({ ...prev, [k]: { status: 'error', error: e?.response?.data?.message || 'Failed to load' } }));
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keys]);

  useEffect(() => { loadAll(); }, [loadAll]);

  const openEdit = (k: string) => {
    const row = rows[k];
    setEditingKey(k);
    setEditTemplate(row?.data?.promptTemplate ?? '');
    setEditDescription(row?.data?.description ?? '');
    setModalError(null);
  };

  const handleSaveEdit = async () => {
    if (!editingKey) return;
    const trimmed = editTemplate.trim();
    if (trimmed.length < MIN_PROMPT_LEN) { setModalError(`Prompt text must be at least ${MIN_PROMPT_LEN} characters.`); return; }
    if (trimmed.length > MAX_PROMPT_LEN) { setModalError(`Prompt text must be under ${MAX_PROMPT_LEN} characters.`); return; }
    setSaving(true); setModalError(null);
    try {
      const updated = await systemPromptApi.update(editingKey, {
        promptTemplate: trimmed, description: editDescription.trim() || undefined,
      });
      setRows(prev => ({ ...prev, [editingKey]: { status: 'found', data: updated } }));
      setSavedKey(editingKey);
      setTimeout(() => setSavedKey(null), 3000);
      setEditingKey(null);
    } catch (e: any) {
      setModalError(e?.response?.data?.message || 'Failed to save prompt.');
    } finally { setSaving(false); }
  };

  const handleDelete = async (k: string) => {
    if (!confirm(`Delete the "${k}" prompt? Lucien will fall back to its built-in default until a new one is created.`)) return;
    setDeletingKey(k);
    try {
      await systemPromptApi.remove(k);
      setRows(prev => ({ ...prev, [k]: { status: 'not-found' } }));
    } catch (e: any) {
      setRows(prev => ({ ...prev, [k]: { ...prev[k], status: 'error', error: e?.response?.data?.message || 'Failed to delete prompt.' } }));
    } finally { setDeletingKey(null); }
  };

  const openAdd = () => {
    setNewKey(''); setNewTemplate(''); setNewDescription(''); setAddError(null); setShowAdd(true);
  };

  const handleAddSave = async () => {
    const key = newKey.trim();
    const trimmed = newTemplate.trim();
    if (!key) { setAddError('Prompt key is required.'); return; }
    if (trimmed.length < MIN_PROMPT_LEN) { setAddError(`Prompt text must be at least ${MIN_PROMPT_LEN} characters.`); return; }
    if (trimmed.length > MAX_PROMPT_LEN) { setAddError(`Prompt text must be under ${MAX_PROMPT_LEN} characters.`); return; }
    setAddSaving(true); setAddError(null);
    try {
      const created = await systemPromptApi.update(key, {
        promptTemplate: trimmed, description: newDescription.trim() || undefined,
      });
      setRows(prev => ({ ...prev, [key]: { status: 'found', data: created } }));
      setCustomKeys(prev => prev.includes(key) ? prev : [...prev, key]);
      setSavedKey(key);
      setTimeout(() => setSavedKey(null), 3000);
      setShowAdd(false);
    } catch (e: any) {
      setAddError(e?.response?.data?.message || 'Failed to create prompt.');
    } finally { setAddSaving(false); }
  };

  const editingRow = editingKey ? rows[editingKey] : undefined;

  return (
    <div className="db-root">
      <AnimatePresence>
        {savedKey && (
          <motion.div key="toast-ok" className="ds-toast is-ok" role="status"
            initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.22 }}
            onClick={() => setSavedKey(null)}>
            <CheckCircle2 size={14} />
            <span>Saved {savedKey}</span>
            <X size={13} className="ds-toast-x" />
          </motion.div>
        )}
      </AnimatePresence>

      <div className="db-content">
        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show">
          <motion.div variants={fadeUp} className="db-page-header">
            <p className="dd-page-context">
              Edit the instructions Lucien loads at runtime
            </p>
            {headerExtra}
          </motion.div>

          <motion.div variants={fadeUp} className="ds-table-card">
            <div className="ds-table-wrap">
              <table className="ds-table is-no-row-hover ps-table">
                <thead>
                  <tr>
                    <th>Key</th>
                    <th>Status</th>
                    <th>Version</th>
                    <th>Description</th>
                    <th>Last updated</th>
                    <th className="is-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {keys.map((k) => {
                    const row = rows[k] ?? { status: 'loading' as RowStatus };
                    return (
                      <tr key={k}>
                        <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, color: 'var(--ink-primary)' }}>
                          {k}
                        </td>
                        <td>
                          {row.status === 'loading' ? (
                            <span className="ds-skel" style={{ height: 22, width: 74, borderRadius: 999, display: 'block' }} />
                          ) : row.status === 'found' ? (
                            <span className={`ds-pill ${row.data!.isActive ? 'is-success' : 'is-neutral'}`}>
                              {row.data!.isActive ? 'ACTIVE' : 'INACTIVE'}
                            </span>
                          ) : row.status === 'not-found' ? (
                            <span className="ds-pill is-neutral">NOT CREATED</span>
                          ) : (
                            <span className="ds-pill is-danger" title={row.error}>ERROR</span>
                          )}
                        </td>
                        <td className="is-mono is-muted">{row.data ? `v${row.data.version}` : '—'}</td>
                        <td style={{ color: 'var(--ink-secondary)' }}>{row.data?.description || '—'}</td>
                        <td className="is-mono is-muted" style={{ whiteSpace: 'nowrap' }}>
                          {row.data ? fmtDate(row.data.updatedAt) : '—'}
                        </td>
                        <td className="is-right">
                          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2, justifyContent: 'flex-end' }}>
                            <button type="button" onClick={() => openEdit(k)} className="ds-table-row-action" title="Edit">
                              <SquarePen size={14} />
                            </button>
                            <button type="button" onClick={() => handleDelete(k)}
                              className="ds-table-row-action is-danger" title="Delete"
                              disabled={deletingKey === k || row.status === 'not-found' || row.status === 'loading'}>
                              {deletingKey === k ? <Loader2 size={14} className="ds-spin" /> : <Trash2 size={14} />}
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </motion.div>
        </motion.div>
      </div>

      <PageFab icon={<Plus size={24} />} label="New prompt" onClick={openAdd} />

      {showAdd && (
        <Modal title="New system prompt"
          subtitle="Creates the prompt for the given key, or overwrites it if one already exists."
          onClose={() => setShowAdd(false)}>
          <FormSection title="Prompt">
            <Input label="Prompt key" value={newKey} onChange={setNewKey}
              placeholder="e.g. lucien.system" required />
          </FormSection>
          <div className="ds-field" style={{ marginBottom: 14 }}>
            <label className="ds-label is-required" htmlFor="new-prompt-text" style={{ marginBottom: 8, display: 'block' }}>Prompt text</label>
            <textarea
              id="new-prompt-text" value={newTemplate}
              onChange={(e) => setNewTemplate(e.target.value.slice(0, MAX_PROMPT_LEN))}
              placeholder="You are Lucien, the recovery-ops assistant. …"
              className="ds-textarea"
              style={{ width: '100%', height: 200, fontFamily: 'var(--font-mono)', fontSize: 13, lineHeight: 1.5, padding: '12px 16px', resize: 'vertical' }}
            />
            <span style={{ fontSize: 11, color: 'var(--ink-tertiary)', display: 'block', marginTop: 4 }}>
              {newTemplate.length}/{MAX_PROMPT_LEN} · minimum {MIN_PROMPT_LEN} characters
            </span>
          </div>
          <div className="ds-field" style={{ marginBottom: 4 }}>
            <label className="ds-label" htmlFor="new-prompt-desc" style={{ marginBottom: 8, display: 'block' }}>Description (optional)</label>
            <textarea
              id="new-prompt-desc" value={newDescription}
              onChange={(e) => setNewDescription(e.target.value.slice(0, MAX_DESC_LEN))}
              placeholder="Notes for the next reviewer — what this prompt is for, when to revisit, etc."
              className="ds-textarea"
              style={{ width: '100%', height: 70, fontSize: 13, padding: '10px 14px', resize: 'vertical' }}
            />
          </div>
          {addError && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, color: 'var(--error)', fontSize: 12.5 }}>
              <AlertCircle size={13} /><span>{addError}</span>
            </div>
          )}
          <ModalFooter onClose={() => setShowAdd(false)} submitting={addSaving}
            onSubmit={handleAddSave} label="Create prompt" />
        </Modal>
      )}

      {editingKey && (
        <Modal title="Edit system prompt" subtitle={editingKey} onClose={() => setEditingKey(null)}>
          <div className="ds-field" style={{ marginBottom: 14 }}>
            <label className="ds-label is-required" htmlFor="edit-prompt-text" style={{ marginBottom: 8, display: 'block' }}>Prompt text</label>
            <textarea
              id="edit-prompt-text" value={editTemplate}
              onChange={(e) => setEditTemplate(e.target.value.slice(0, MAX_PROMPT_LEN))}
              placeholder="You are Lucien, the recovery-ops assistant. …"
              className="ds-textarea"
              style={{ width: '100%', height: 260, fontFamily: 'var(--font-mono)', fontSize: 13, lineHeight: 1.5, padding: '12px 16px', resize: 'vertical' }}
            />
            <span style={{ fontSize: 11, color: editTemplate.trim().length > 0 && editTemplate.trim().length < MIN_PROMPT_LEN ? 'var(--warning)' : 'var(--ink-tertiary)', display: 'block', marginTop: 4 }}>
              {editTemplate.length}/{MAX_PROMPT_LEN} · minimum {MIN_PROMPT_LEN} characters
            </span>
          </div>
          <div className="ds-field" style={{ marginBottom: 4 }}>
            <label className="ds-label" htmlFor="edit-prompt-desc" style={{ marginBottom: 8, display: 'block' }}>Description (optional)</label>
            <textarea
              id="edit-prompt-desc" value={editDescription}
              onChange={(e) => setEditDescription(e.target.value.slice(0, MAX_DESC_LEN))}
              placeholder="Notes for the next reviewer — what this prompt is for, when to revisit, etc."
              className="ds-textarea"
              style={{ width: '100%', height: 70, fontSize: 13, padding: '10px 14px', resize: 'vertical' }}
            />
          </div>
          {editingRow?.data && (
            <p style={{ fontSize: 11, color: 'var(--ink-tertiary)', marginTop: 8 }}>
              Version {editingRow.data.version} · {editingRow.data.isActive ? 'Active' : 'Inactive'} · Last updated {new Date(editingRow.data.updatedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}
            </p>
          )}
          {modalError && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, color: 'var(--error)', fontSize: 12.5 }}>
              <AlertCircle size={13} /><span>{modalError}</span>
            </div>
          )}
          <ModalFooter onClose={() => setEditingKey(null)} submitting={saving}
            onSubmit={handleSaveEdit} label="Save changes" />
        </Modal>
      )}
    </div>
  );
}
