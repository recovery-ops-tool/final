import { useEffect, useState, useRef, type ReactNode } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { FileText, Trash2, Loader2, AlertCircle, X, Plus, UploadCloud, SquarePen } from 'lucide-react';
import { ragApi } from '../api/ragApi';
import type { RagDocumentResponse, RagStatus } from '../api/ragApi';
import { PageFab } from '../components/PageFab';
import { Modal, ModalFooter, FormSection, Input } from './PlatformSetupShared';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';

const STATUS_VARIANT: Record<RagStatus, string> = {
  PENDING:    'is-neutral',
  PROCESSING: 'is-warn',
  ACTIVE:     'is-success',
  FAILED:     'is-danger',
  SUPERSEDED: 'is-neutral',
};

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

export default function RagDocumentsPage({ headerExtra }: { headerExtra?: ReactNode }) {
  const [docs, setDocs]             = useState<RagDocumentResponse[]>([]);
  const [loading, setLoading]       = useState(true);
  const [error, setError]           = useState<string | null>(null);
  const [showUpload, setShowUpload] = useState(false);
  const [title, setTitle]           = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile]             = useState<File | null>(null);
  const [uploading, setUploading]   = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [editingDoc, setEditingDoc]     = useState<RagDocumentResponse | null>(null);
  const [editTitle, setEditTitle]       = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [savingEdit, setSavingEdit]     = useState(false);
  const [editError, setEditError]       = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const load = async () => {
    setLoading(true); setError(null);
    try {
      const list = await ragApi.list();
      setDocs(list || []);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to load documents.');
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const openUpload = () => {
    setTitle(''); setDescription(''); setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
    setError(null); setShowUpload(true);
  };

  const handleUpload = async () => {
    if (!title.trim() || !file) return;
    setUploading(true); setError(null);
    try {
      await ragApi.upload(file, title.trim(), description.trim() || undefined);
      setShowUpload(false);
      await load();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to upload document.');
    } finally { setUploading(false); }
  };

  const openEdit = (doc: RagDocumentResponse) => {
    setEditingDoc(doc);
    setEditTitle(doc.title);
    setEditDescription(doc.description ?? '');
    setEditError(null);
  };

  const handleEditSave = async () => {
    if (!editingDoc) return;
    if (!editTitle.trim()) { setEditError('Title is required.'); return; }
    setSavingEdit(true); setEditError(null);
    try {
      const updated = await ragApi.update(editingDoc.id, editTitle.trim(), editDescription.trim() || undefined);
      setDocs(prev => prev.map(d => d.id === updated.id ? updated : d));
      setEditingDoc(null);
    } catch (e: any) {
      setEditError(e?.response?.data?.message || 'Failed to update document.');
    } finally { setSavingEdit(false); }
  };

  const handleDeactivate = async (id: string, name: string) => {
    if (!confirm(`Deactivate "${name}"? This removes it from Lucien's retrieval corpus.`)) return;
    setDeletingId(id);
    try {
      await ragApi.supersede(id);
      await load();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to deactivate document.');
    } finally { setDeletingId(null); }
  };

  return (
    <div className="db-root">
      <AnimatePresence>
        {error && (
          <motion.div key="toast-err" className="db-error-banner" role="alert" style={{ marginBottom: 24 }}
            initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.22 }}>
            <AlertCircle size={16} aria-hidden="true" className="db-error-icon" />
            <div className="db-error-body">
              <span className="db-error-title">{error}</span>
            </div>
            <button className="db-error-retry" onClick={() => setError(null)} aria-label="Dismiss">
              <X size={14} aria-hidden="true" />
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="db-content">
        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show">
          <motion.div variants={fadeUp} className="db-page-header">
            <p className="dd-page-context">
              Compliance documents Lucien retrieves from — platform-wide
            </p>
            {headerExtra}
          </motion.div>

          {showUpload && (
            <Modal title="New document"
              subtitle="Upload a file to add to Lucien's retrieval corpus."
              onClose={() => setShowUpload(false)}>
              <FormSection title="Document">
                <Input label="Title" value={title} onChange={setTitle}
                  placeholder="e.g. RBI Fair Practices Code §3.2" required />
                <div className="ds-field">
                  <label className="ds-label">File</label>
                  <input id="rag-file" ref={fileInputRef} type="file" required
                    onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                    className="ds-input" style={{ width: '100%', padding: '8px 10px' }} />
                  {file && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--ink-tertiary)', marginTop: 6 }}>
                      <UploadCloud size={13} /> {file.name} · {(file.size / 1024).toFixed(1)} KB
                    </div>
                  )}
                </div>
                <Input label="Description" value={description} onChange={setDescription}
                  placeholder="Short note on what this document covers, and why it belongs in Lucien's corpus." />
              </FormSection>
              <ModalFooter onClose={() => setShowUpload(false)} submitting={uploading}
                onSubmit={handleUpload} label="Upload & queue" />
            </Modal>
          )}

          {editingDoc && (
            <Modal title="Edit document"
              subtitle="Updates title and description only — the underlying file is unchanged."
              onClose={() => setEditingDoc(null)}>
              <FormSection title="Document">
                <Input label="Title" value={editTitle} onChange={setEditTitle}
                  placeholder="e.g. RBI Fair Practices Code §3.2" required />
                <Input label="Description" value={editDescription} onChange={setEditDescription}
                  placeholder="Short note on what this document covers, and why it belongs in Lucien's corpus." />
              </FormSection>
              {editError && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, color: 'var(--error)', fontSize: 12.5 }}>
                  <AlertCircle size={13} /><span>{editError}</span>
                </div>
              )}
              <ModalFooter onClose={() => setEditingDoc(null)} submitting={savingEdit}
                onSubmit={handleEditSave} label="Save changes" />
            </Modal>
          )}

          <motion.div variants={fadeUp} className="ds-table-card">
            <div className="ds-table-wrap">
              <table className="ds-table is-no-row-hover ps-table">
                <thead>
                  <tr>
                    <th>Title</th>
                    <th>Status</th>
                    <th>Content type</th>
                    <th className="is-right">Added</th>
                    <th className="is-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    Array.from({ length: 8 }).map((_, i) => (
                      <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                        <td><span className="ds-skel" style={{ height: 14, width: '70%', display: 'block' }} /></td>
                        <td><span className="ds-skel" style={{ height: 22, width: 64, borderRadius: 999, display: 'block' }} /></td>
                        <td><span className="ds-skel" style={{ height: 14, width: 80, display: 'block' }} /></td>
                        <td className="is-right"><span className="ds-skel" style={{ height: 14, width: 64, marginLeft: 'auto', display: 'block' }} /></td>
                        <td className="is-right"><span className="ds-skel" style={{ height: 24, width: 24, borderRadius: 6, marginLeft: 'auto', display: 'block' }} /></td>
                      </tr>
                    ))
                  ) : docs.length === 0 ? (
                    <tr>
                      <td colSpan={5}>
                        <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show">
                          <span className="ds-empty-icon"><FileText size={20} /></span>
                          <span className="ds-empty-title">No documents yet</span>
                          <span className="ds-empty-sub">Upload one to start building Lucien's corpus.</span>
                        </motion.div>
                      </td>
                    </tr>
                  ) : (
                    <AnimatePresence mode="popLayout">
                      {docs.map((d, idx) => (
                        <motion.tr
                          key={d.id}
                          initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
                          transition={{ duration: 0.28, delay: idx * 0.02 }}
                        >
                          <td>
                            <div style={{ fontWeight: 500, color: 'var(--ink-primary)' }}>{d.title}</div>
                            {d.description && (
                              <div style={{ fontSize: 11.5, color: 'var(--ink-tertiary)', marginTop: 2, maxWidth: 360 }}>{d.description}</div>
                            )}
                            {d.status === 'FAILED' && d.errorMessage && (
                              <div style={{ fontSize: 11, color: 'var(--danger)', marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
                                <AlertCircle size={11} /> {d.errorMessage}
                              </div>
                            )}
                          </td>
                          <td>
                            <span className={`ds-pill ${STATUS_VARIANT[d.status]}`}>
                              {d.status}
                            </span>
                          </td>
                          <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--ink-tertiary)' }}>
                            {d.contentType || '—'}
                          </td>
                          <td className="is-right" style={{ fontFamily: 'var(--font-mono)', color: 'var(--ink-tertiary)' }}>
                            {new Date(d.createdAt).toLocaleDateString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric' })}
                          </td>
                          <td className="is-right">
                            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2, justifyContent: 'flex-end' }}>
                              <button type="button" onClick={() => openEdit(d)} className="ds-table-row-action" title="Edit">
                                <SquarePen size={14} />
                              </button>
                              <button
                                type="button"
                                className="ds-table-row-action is-danger"
                                onClick={() => handleDeactivate(d.id, d.title)}
                                disabled={deletingId === d.id || d.status === 'SUPERSEDED'}
                                aria-label="Deactivate document"
                                title={d.status === 'SUPERSEDED' ? 'Already deactivated' : 'Deactivate'}
                              >
                                {deletingId === d.id ? <Loader2 size={14} className="ds-spin" /> : <Trash2 size={14} />}
                              </button>
                            </div>
                          </td>
                        </motion.tr>
                      ))}
                    </AnimatePresence>
                  )}
                </tbody>
              </table>
            </div>
          </motion.div>
        </motion.div>
      </div>

      <PageFab icon={<Plus size={24} />} label="New document" onClick={openUpload} />
    </div>
  );
}
