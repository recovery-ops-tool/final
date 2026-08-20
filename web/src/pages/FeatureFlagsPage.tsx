import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { Flag, Plus, Loader2, AlertCircle, X, Building2, Globe, ChevronDown, SquarePen, ToggleLeft, ToggleRight, Trash2 } from 'lucide-react';
import { featureFlagsApi } from '../api/featureFlagsApi';
import type { FeatureFlag } from '../api/featureFlagsApi';
import { platformApi, type OrganizationSummary } from '../api/platformApi';
import { Modal, ModalFooter, FormSection, Input } from './PlatformSetupShared';
import { PageFab } from '../components/PageFab';
import '../styles/AppPage.css';
import '../styles/PlatformSetupPage.css';
import './Dashboard.css';
import '../styles/FeatureFlagsPage.css';

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

type NewFlagScope = 'GLOBAL' | 'ORG';
type Tab = 'global' | 'overrides';

export default function FeatureFlagsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = (searchParams.get('tab') as Tab) || 'global';
  const setTab = (t: Tab) => setSearchParams({ tab: t });
  const [flags, setFlags]               = useState<FeatureFlag[]>([]);
  const [loading, setLoading]           = useState(true);
  const [error, setError]               = useState<string | null>(null);
  const [showAdd, setShowAdd]           = useState(false);
  const [newKey, setNewKey]             = useState('');
  const [newEnabled, setNewEnabled]     = useState(false);
  const [newDescription, setNewDescription] = useState('');
  const [newScope, setNewScope]         = useState<NewFlagScope>('GLOBAL');
  const [newOrgId, setNewOrgId]         = useState('');
  const [saving, setSaving]             = useState(false);
  const [togglingKey, setTogglingKey]   = useState<string | null>(null);
  const [editingFlag, setEditingFlag]   = useState<FeatureFlag | null>(null);
  const [editDescription, setEditDescription] = useState('');
  const [savingEdit, setSavingEdit]     = useState(false);
  const [orgs, setOrgs]                 = useState<OrganizationSummary[]>([]);
  const [selectedOrgId, setSelectedOrgId] = useState('');
  const [orgFlags, setOrgFlags]         = useState<FeatureFlag[]>([]);
  const [loadingOrgFlags, setLoadingOrgFlags] = useState(false);
  const [deletingKey, setDeletingKey]   = useState<string | null>(null);

  const load = async () => {
    setLoading(true); setError(null);
    try { setFlags(await featureFlagsApi.list(null) || []); }
    catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to load flags.');
      setFlags([]);
    } finally { setLoading(false); }
  };

  const loadOrgFlags = async (orgId: string) => {
    if (!orgId) { setOrgFlags([]); return; }
    setLoadingOrgFlags(true); setError(null);
    try { setOrgFlags(await featureFlagsApi.list(orgId) || []); }
    catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to load organization overrides.');
      setOrgFlags([]);
    } finally { setLoadingOrgFlags(false); }
  };

  useEffect(() => {
    load();
    platformApi.listOrganizations().then(setOrgs).catch(() => {});
  }, []);

  useEffect(() => { loadOrgFlags(selectedOrgId); }, [selectedOrgId]);

  const openAdd = () => {
    setNewKey(''); setNewEnabled(false); setNewDescription('');
    setNewScope('GLOBAL'); setNewOrgId('');
    setError(null); setShowAdd(true);
  };

  const handleAdd = async () => {
    if (!newKey.trim()) return;
    if (newScope === 'ORG' && !newOrgId.trim()) return;
    setSaving(true); setError(null);
    try {
      await featureFlagsApi.upsert({
        organizationId: newScope === 'GLOBAL' ? null : newOrgId.trim(),
        flagKey: newKey.trim(),
        enabled: newEnabled,
        description: newDescription.trim() || undefined,
      });
      setShowAdd(false);
      if (newScope === 'GLOBAL') await load();
      else if (newOrgId.trim() === selectedOrgId) await loadOrgFlags(selectedOrgId);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to create flag.');
    } finally { setSaving(false); }
  };

  const handleToggle = async (flag: FeatureFlag) => {
    setTogglingKey(flag.flagKey); setError(null);
    try {
      await featureFlagsApi.upsert({
        organizationId: flag.organizationId,
        flagKey: flag.flagKey,
        enabled: !flag.enabled,
        description: flag.description ?? undefined,
      });
      // Backend returns a confirmation message, not the updated flag — update locally.
      const patch = (list: FeatureFlag[]) => list.map((f) =>
        f.flagKey === flag.flagKey && f.organizationId === flag.organizationId
          ? { ...f, enabled: !flag.enabled }
          : f,
      );
      if (flag.organizationId === null) setFlags(patch);
      else setOrgFlags(patch);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to toggle flag.');
    } finally { setTogglingKey(null); }
  };

  const openEdit = (flag: FeatureFlag) => {
    setEditingFlag(flag);
    setEditDescription(flag.description ?? '');
    setError(null);
  };

  const handleEditSave = async () => {
    if (!editingFlag) return;
    setSavingEdit(true); setError(null);
    try {
      await featureFlagsApi.upsert({
        organizationId: editingFlag.organizationId,
        flagKey: editingFlag.flagKey,
        enabled: editingFlag.enabled,
        description: editDescription.trim() || undefined,
      });
      const orgId = editingFlag.organizationId;
      setEditingFlag(null);
      if (orgId === null) await load();
      else await loadOrgFlags(orgId);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to update flag.');
    } finally { setSavingEdit(false); }
  };

  const handleDeleteFlag = async (flag: FeatureFlag) => {
    const confirmMsg = flag.organizationId
      ? `Delete the "${flag.flagKey}" override for this organization? It will revert to the plan default.`
      : `Delete the global flag "${flag.flagKey}"? This cannot be undone.`;
    if (!confirm(confirmMsg)) return;
    setDeletingKey(flag.flagKey); setError(null);
    try {
      await featureFlagsApi.deleteOverride(flag.organizationId, flag.flagKey);
      if (flag.organizationId) await loadOrgFlags(flag.organizationId);
      else await load();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to delete flag.');
    } finally { setDeletingKey(null); }
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
          <motion.div variants={fadeUp} className="db-page-header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 16, marginBottom: 20 }}>
            <p className="dd-page-context" style={{ margin: 0 }}>
              Manage feature flags for the platform and individual organizations
            </p>
            <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
              {tab === 'overrides' && (
                <div className="ps-select-wrap" style={{ maxWidth: 340, minWidth: 220, position: 'relative' }}>
                  <select value={selectedOrgId} onChange={e => setSelectedOrgId(e.target.value)}
                    className="ds-select" style={{ width: '100%' }}>
                    <option value="">Select an organization…</option>
                    {orgs.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                  <ChevronDown size={13} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--ink-tertiary)', pointerEvents: 'none' }} />
                </div>
              )}
              <div className="db-kpi-toggle" role="group" aria-label="Feature flag view">
                <button
                  type="button"
                  className={`db-kpi-toggle-btn${tab === 'global' ? ' is-active' : ''}`}
                  onClick={() => setTab('global')}
                  aria-pressed={tab === 'global'}
                >
                  Flags
                </button>
                <button
                  type="button"
                  className={`db-kpi-toggle-btn${tab === 'overrides' ? ' is-active' : ''}`}
                  onClick={() => setTab('overrides')}
                  aria-pressed={tab === 'overrides'}
                >
                  Overrides
                </button>
              </div>
            </div>
          </motion.div>

          {showAdd && (
            <Modal title="New feature flag"
              subtitle="Creates a flag for the whole platform, or scoped to one organization."
              onClose={() => setShowAdd(false)}>
              <FormSection title="Scope">
                <div className="ds-field">
                  <label className="ds-label">Applies to</label>
                  <div className="ps-select-wrap">
                    <select value={newScope} onChange={e => setNewScope(e.target.value as NewFlagScope)}
                      className="ds-select" style={{ width: '100%' }}>
                      <option value="GLOBAL">Global (whole platform)</option>
                      <option value="ORG">Specific organization</option>
                    </select>
                    <ChevronDown size={13} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--ink-tertiary)', pointerEvents: 'none' }} />
                  </div>
                </div>
                {newScope === 'ORG' && (
                  <Input label="Organization UUID" value={newOrgId} onChange={setNewOrgId}
                    placeholder="Organization UUID" required />
                )}
              </FormSection>
              <FormSection title="Flag">
                <Input label="Flag key" value={newKey} onChange={setNewKey}
                  placeholder="e.g. payments.upi-deeplink" required />
                <Input label="Description" value={newDescription} onChange={setNewDescription}
                  placeholder="What does this flag control?" />
              </FormSection>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontSize: 13, marginBottom: 4 }}>
                <input type="checkbox" className="ds-checkbox" checked={newEnabled}
                  onChange={(e) => setNewEnabled(e.target.checked)} />
                <span>Enabled on create</span>
              </label>
              <ModalFooter onClose={() => setShowAdd(false)} submitting={saving}
                onSubmit={handleAdd} label="Create flag" />
            </Modal>
          )}

          {editingFlag && (
            <Modal title="Edit feature flag"
              subtitle={editingFlag.flagKey}
              onClose={() => setEditingFlag(null)}>
              <FormSection title="Flag">
                <Input label="Description" value={editDescription} onChange={setEditDescription}
                  placeholder="What does this flag control?" />
              </FormSection>
              <ModalFooter onClose={() => setEditingFlag(null)} submitting={savingEdit}
                onSubmit={handleEditSave} label="Save changes" />
            </Modal>
          )}

          <AnimatePresence mode="wait">
            <motion.div
              key={tab}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2 }}
            >
              {tab === 'global' ? (
                <div className="ds-table-card">
                  <div className="ds-table-wrap">
                    <table className="ds-table is-no-row-hover ps-table">
                      <thead>
                        <tr>
                          <th>Key</th>
                          <th>Status</th>
                          <th>Scope</th>
                          <th>Description</th>
                          <th className="is-right">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {loading ? (
                          Array.from({ length: 4 }).map((_, i) => (
                            <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                              <td><span className="ds-skel" style={{ height: 14, width: '70%', display: 'block' }} /></td>
                              <td><span className="ds-skel" style={{ height: 22, width: 64, borderRadius: 999, display: 'block' }} /></td>
                              <td><span className="ds-skel" style={{ height: 22, width: 64, borderRadius: 999, display: 'block' }} /></td>
                              <td><span className="ds-skel" style={{ height: 14, width: '60%', display: 'block' }} /></td>
                              <td className="is-right"><span className="ds-skel" style={{ height: 32, width: 72, borderRadius: 8, marginLeft: 'auto', display: 'block' }} /></td>
                            </tr>
                          ))
                        ) : flags.length === 0 ? (
                          <tr>
                            <td colSpan={5}>
                              <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show">
                                <span className="ds-empty-icon"><Flag size={20} /></span>
                                <span className="ds-empty-title">No global flags yet</span>
                                <span className="ds-empty-sub">Create one with the + button.</span>
                              </motion.div>
                            </td>
                          </tr>
                        ) : (
                          <AnimatePresence mode="popLayout">
                            {flags.map((f, idx) => (
                              <motion.tr
                                key={`${f.organizationId ?? 'global'}-${f.flagKey}`}
                                initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
                                transition={{ duration: 0.28, delay: idx * 0.02 }}
                              >
                                <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, color: 'var(--ink-primary)' }}>
                                  {f.flagKey}
                                </td>
                                <td>
                                  <span className={`ds-pill ${f.enabled ? 'is-success' : 'is-neutral'}`}>
                                    {f.enabled ? 'ENABLED' : 'DISABLED'}
                                  </span>
                                </td>
                                <td>
                                  <span className="ds-pill is-info" style={{ gap: 4 }}>
                                    {f.organizationId
                                      ? <><Building2 size={11} /> {f.organizationId.slice(0, 8)}…</>
                                      : <><Globe size={11} /> Global</>}
                                  </span>
                                </td>
                                <td style={{ color: 'var(--ink-secondary)' }}>
                                  {f.description || '—'}
                                </td>
                                <td className="is-right">
                                  <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2, justifyContent: 'flex-end' }}>
                                    <button type="button" onClick={() => handleToggle(f)} className="ds-table-row-action"
                                      disabled={togglingKey === f.flagKey}
                                      title={f.enabled ? 'Disable' : 'Enable'}>
                                      {togglingKey === f.flagKey
                                        ? <Loader2 size={16} className="ds-spin" />
                                        : f.enabled
                                          ? <ToggleRight size={16} style={{ color: 'var(--success)' }} />
                                          : <ToggleLeft size={16} />}
                                    </button>
                                    <button type="button" onClick={() => openEdit(f)} className="ds-table-row-action" title="Edit">
                                      <SquarePen size={14} />
                                    </button>
                                    <button type="button" onClick={() => handleDeleteFlag(f)}
                                      className="ds-table-row-action is-danger" title="Delete"
                                      disabled={deletingKey === f.flagKey}>
                                      {deletingKey === f.flagKey ? <Loader2 size={14} className="ds-spin" /> : <Trash2 size={14} />}
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
                </div>
              ) : (
                <>
                  <div className="ds-table-card">
                    <div className="ds-table-wrap">
                      <table className="ds-table is-no-row-hover ps-table">
                      <thead>
                        <tr>
                          <th>Key</th>
                          <th>Status</th>
                          <th>Description</th>
                          <th className="is-right">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {!selectedOrgId ? (
                          <tr>
                            <td colSpan={4}>
                              <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show">
                                <span className="ds-empty-icon"><Building2 size={20} /></span>
                                <span className="ds-empty-title">Pick an organization</span>
                                <span className="ds-empty-sub">Select one above to see its manual overrides.</span>
                              </motion.div>
                            </td>
                          </tr>
                        ) : loadingOrgFlags ? (
                          Array.from({ length: 2 }).map((_, i) => (
                            <tr key={i} style={{ opacity: 1 - i * 0.08 }}>
                              <td><span className="ds-skel" style={{ height: 14, width: '70%', display: 'block' }} /></td>
                              <td><span className="ds-skel" style={{ height: 22, width: 64, borderRadius: 999, display: 'block' }} /></td>
                              <td><span className="ds-skel" style={{ height: 14, width: '60%', display: 'block' }} /></td>
                              <td className="is-right"><span className="ds-skel" style={{ height: 24, width: 60, borderRadius: 8, marginLeft: 'auto', display: 'block' }} /></td>
                            </tr>
                          ))
                        ) : orgFlags.length === 0 ? (
                          <tr>
                            <td colSpan={4}>
                              <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show">
                                <span className="ds-empty-icon"><Flag size={20} /></span>
                                <span className="ds-empty-title">No overrides for this organization</span>
                                <span className="ds-empty-sub">It's using the plan default for every flag.</span>
                              </motion.div>
                            </td>
                          </tr>
                        ) : (
                          <AnimatePresence mode="popLayout">
                            {orgFlags.map((f, idx) => (
                              <motion.tr
                                key={`${f.organizationId}-${f.flagKey}`}
                                initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
                                transition={{ duration: 0.28, delay: idx * 0.02 }}
                              >
                                <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, color: 'var(--ink-primary)' }}>
                                  {f.flagKey}
                                </td>
                                <td>
                                  <span className={`ds-pill ${f.enabled ? 'is-success' : 'is-neutral'}`}>
                                    {f.enabled ? 'ENABLED' : 'DISABLED'}
                                  </span>
                                </td>
                                <td style={{ color: 'var(--ink-secondary)' }}>
                                  {f.description || '—'}
                                </td>
                                <td className="is-right">
                                  <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2, justifyContent: 'flex-end' }}>
                                    <button type="button" onClick={() => handleToggle(f)} className="ds-table-row-action"
                                      disabled={togglingKey === f.flagKey}
                                      title={f.enabled ? 'Disable' : 'Enable'}>
                                      {togglingKey === f.flagKey
                                        ? <Loader2 size={16} className="ds-spin" />
                                        : f.enabled
                                          ? <ToggleRight size={16} style={{ color: 'var(--success)' }} />
                                          : <ToggleLeft size={16} />}
                                    </button>
                                    <button type="button" onClick={() => openEdit(f)} className="ds-table-row-action" title="Edit">
                                      <SquarePen size={14} />
                                    </button>
                                    <button type="button" onClick={() => handleDeleteFlag(f)}
                                      className="ds-table-row-action is-danger" title="Delete override"
                                      disabled={deletingKey === f.flagKey}>
                                      {deletingKey === f.flagKey ? <Loader2 size={14} className="ds-spin" /> : <Trash2 size={14} />}
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
                  </div>
                </>
              )}
            </motion.div>
          </AnimatePresence>
        </motion.div>
      </div>

      <PageFab
        icon={<Plus size={24} />}
        label="New feature flag"
        onClick={openAdd}
      />
    </div>
  );
}