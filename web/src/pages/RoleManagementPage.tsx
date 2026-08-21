import type { FormEvent } from 'react';
import { useState, useEffect, useMemo } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { rolesApi } from '../api/rolesApi';
import { permissionsApi } from '../api/permissionsApi';
import { useAuth } from '../AuthContext';
import { usePermissions } from '../hooks/usePermissions';
import type { RoleResponse, PermissionResponse } from '../types';
import { ShieldCheck, Plus, X, Loader2, AlertCircle } from 'lucide-react';
import { RoleCard, type EditState } from './RoleManagementRoleCard';
import '../styles/AppPage.css';
import './Dashboard.css';
import '../styles/RoleManagementPage.css';

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

export default function RoleManagementPage() {
  const { user } = useAuth();
  const { hasPermission } = usePermissions();
  const canAssign = hasPermission('ROLE_ASSIGN');
  const rawRole = user?.roles?.[0]?.name?.replace('ROLE_', '').trim();
  const isPlatformAdmin = rawRole === 'PLATFORM_ADMIN';

  const [roles,          setRoles]          = useState<RoleResponse[]>([]);
  const [allPermissions, setAllPermissions] = useState<PermissionResponse[]>([]);
  const [loading,        setLoading]        = useState(true);
  const [error,          setError]          = useState<string | null>(null);
  const [expandedId,     setExpandedId]     = useState<string | null>(null);
  const [editState,      setEditState]      = useState<EditState | null>(null);
  const [saving,         setSaving]         = useState(false);
  const [saveError,      setSaveError]      = useState<string | null>(null);
  const [permSearch,     setPermSearch]     = useState('');
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newRoleName,    setNewRoleName]    = useState('');
  const [newRoleDesc,    setNewRoleDesc]    = useState('');
  const [creating,       setCreating]       = useState(false);

  useEffect(() => {
    const loadAll = async () => {
      setLoading(true); setError(null);
      try {
        const [rolesData, permsData] = await Promise.all([
          rolesApi.listRoles(), permissionsApi.listPermissions(),
        ]);
        setRoles(rolesData || []);
        setAllPermissions(permsData || []);
      } catch { setError('Failed to load roles and permissions.'); }
      finally { setLoading(false); }
    };
    loadAll();
  }, []);

  const systemRoles = useMemo(() => roles.filter(r =>  r.systemRole), [roles]);
  const customRoles = useMemo(() => roles.filter(r => !r.systemRole), [roles]);

  const canEditRole = (role: RoleResponse) => {
    if (!canAssign) return false;
    if (isPlatformAdmin) return true;
    if (role.systemRole) return false;
    return role.organizationId === user?.organizationId;
  };

  const groupedPermissions = useMemo(() => {
    const groups: Record<string, PermissionResponse[]> = {};
    for (const p of allPermissions) { const key = p.resource || 'OTHER'; (groups[key] ??= []).push(p); }
    return groups;
  }, [allPermissions]);

  const filteredGrouped = useMemo(() => {
    if (!permSearch.trim()) return groupedPermissions;
    const q = permSearch.toLowerCase();
    const result: Record<string, PermissionResponse[]> = {};
    for (const [res, perms] of Object.entries(groupedPermissions)) {
      const filtered = perms.filter(p =>
        p.name.toLowerCase().includes(q) ||
        p.action.toLowerCase().includes(q) ||
        res.toLowerCase().includes(q),
      );
      if (filtered.length) result[res] = filtered;
    }
    return result;
  }, [groupedPermissions, permSearch]);

  const startEdit  = (role: RoleResponse) => {
    setEditState({ roleId: role.id, selected: new Set(role.permissions.map(p => p.id)) });
    setPermSearch(''); setSaveError(null);
  };
  const cancelEdit = () => { setEditState(null); setPermSearch(''); setSaveError(null); };
  const togglePermission = (permId: string) => {
    if (!editState) return;
    const next = new Set(editState.selected);
    if (next.has(permId)) next.delete(permId); else next.add(permId);
    setEditState({ ...editState, selected: next });
  };

  const savePermissions = async () => {
    if (!editState) return;
    setSaving(true); setSaveError(null);
    try {
      const updated = await rolesApi.updateRolePermissions(editState.roleId, Array.from(editState.selected));
      setRoles(prev => prev.map(r => r.id === editState.roleId ? updated : r));
      setEditState(null);
    } catch { setSaveError('Failed to save permissions. Please try again.'); }
    finally { setSaving(false); }
  };

  const handleCreateRole = async (e: FormEvent) => {
    e.preventDefault();
    if (!newRoleName.trim()) return;
    setCreating(true); setSaveError(null);
    try {
      const created = await rolesApi.createRole({
        name: newRoleName.trim(), description: newRoleDesc.trim(),
      });
      setRoles(prev => [...prev, created]);
      setNewRoleName(''); setNewRoleDesc(''); setShowCreateForm(false);
    } catch { setSaveError('Failed to create role.'); }
    finally { setCreating(false); }
  };

  const roleCardProps = {
    saving, saveError, permSearch, filteredGrouped,
    onCancelEdit: cancelEdit, onTogglePerm: togglePermission,
    onSave: savePermissions, onPermSearch: setPermSearch,
  };

  return (
    <div className="db-root db-fill-root" style={{ height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
      <div className="db-content" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden', flex: 1, paddingBottom: 36 }}>
        <AnimatePresence>
          {error && (
            <motion.div key="toast-err" className="db-error-banner" role="alert" style={{ marginBottom: 24, flexShrink: 0 }}
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

        <div className="db-page-header">
          <div className="db-page-header-left">
            <p className="dd-page-context">
              {!loading ? (
                <>You have <strong>{roles.length} roles</strong> defined for access control settings.</>
              ) : (
                'Manage access control'
              )}
            </p>
          </div>
          <div className="db-list-page-actions">
            <div className="db-list-btn-group">
              {canAssign && (
                <button
                  type="button"
                  onClick={() => { setShowCreateForm(!showCreateForm); setSaveError(null); }}
                  className={`ds-btn ${showCreateForm ? 'is-secondary' : 'is-primary'}`}
                >
                  {showCreateForm ? <><X size={14} /> Cancel</> : <><Plus size={14} /> New role</>}
                </button>
              )}
            </div>
          </div>
        </div>

        <motion.div className="db-inner" variants={stagger} initial="hidden" animate="show" style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <motion.div variants={fadeUp} className="ds-card db-card" style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, padding: 0, overflow: 'hidden' }}>
            <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column' }}>
              <AnimatePresence>
            {showCreateForm && (
              <motion.form variants={fadeUp} initial="hidden" animate="show" exit={{ opacity: 0, y: -10 }} onSubmit={handleCreateRole} style={{ borderBottom: '4px solid var(--bg-subtle)' }}>
                <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--border-subtle)', background: 'var(--bg-subtle)' }}>
                  <h2 className="db-card-title">Create new role</h2>
                </div>
                <div style={{ padding: 24 }}>
                  {saveError && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderRadius: 8, background: 'var(--danger-subtle)', border: '1px solid color-mix(in srgb, var(--danger) 30%, transparent)', color: 'var(--danger)', fontSize: 12.5, marginBottom: 20 }}>
                      <AlertCircle size={14} /><span>{saveError}</span>
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap', marginBottom: 24 }}>
                    <div className="ds-field" style={{ flex: 1, minWidth: 200, marginBottom: 0 }}>
                      <label className="ds-label is-required" style={{ display: 'block', marginBottom: 6 }}>Role name</label>
                      <input
                        value={newRoleName}
                        onChange={e => setNewRoleName(e.target.value)}
                        placeholder="e.g. TEAM_LEAD"
                        required
                        className="ds-input"
                        style={{ width: '100%', fontFamily: 'var(--font-mono)' }}
                      />
                    </div>
                    <div className="ds-field" style={{ flex: 2, minWidth: 300, marginBottom: 0 }}>
                      <label className="ds-label" style={{ display: 'block', marginBottom: 6 }}>Description</label>
                      <input
                        value={newRoleDesc}
                        onChange={e => setNewRoleDesc(e.target.value)}
                        placeholder="Optional description"
                        className="ds-input"
                        style={{ width: '100%' }}
                      />
                    </div>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', borderTop: '1px solid var(--border-subtle)', paddingTop: 16 }}>
                    <button
                      type="submit"
                      disabled={creating || !newRoleName.trim()}
                      className="ds-btn is-primary"
                    >
                      {creating ? <Loader2 size={14} className="ds-spin" style={{ marginRight: 6 }} /> : <Plus size={14} style={{ marginRight: 6 }} />}
                      Create role
                    </button>
                  </div>
                </div>
              </motion.form>
            )}
          </AnimatePresence>

          {loading ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16, padding: 24 }}>
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="ds-card db-card" style={{ padding: 24 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                    <div className="ds-skel" style={{ width: 32, height: 32, borderRadius: 8 }} />
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 1 }}>
                      <div className="ds-skel" style={{ height: 14, width: '30%' }} />
                      <div className="ds-skel" style={{ height: 11, width: '50%' }} />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : roles.length === 0 ? (
            <motion.div className="ds-empty" variants={fadeIn} initial="hidden" animate="show" style={{ padding: '80px 0' }}>
              <ShieldCheck size={32} className="ds-empty-icon" />
              <span className="ds-empty-title">No roles found</span>
              <span className="ds-empty-sub">Create your first custom role to get started.</span>
            </motion.div>
          ) : (
            <motion.div variants={fadeUp} style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, padding: 0 }}>
              <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, padding: 0 }}>
                <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
                  <div style={{ display: 'flex', flexDirection: 'column' }}>
                    {customRoles.length > 0 && (
                      <div>
                        <div style={{ padding: '16px 16px 8px', background: 'var(--bg-surface)', borderBottom: '1px solid var(--border-subtle)', position: 'sticky', top: 0, zIndex: 10 }}>
                          <h3 className="db-section-label" style={{ margin: 0 }}>Custom roles</h3>
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column' }}>
                          {customRoles.map(role => (
                            <motion.div key={role.id} variants={fadeUp}>
                              <RoleCard
                                role={role}
                                expanded={expandedId === role.id}
                                editState={editState?.roleId === role.id ? editState : null}
                                canEdit={canEditRole(role)}
                                onToggle={() => setExpandedId(expandedId === role.id ? null : role.id)}
                                onStartEdit={() => startEdit(role)}
                                {...roleCardProps}
                              />
                            </motion.div>
                          ))}
                        </div>
                      </div>
                    )}

                    {systemRoles.length > 0 && (
                      <div>
                        <div style={{ padding: '16px 16px 8px', background: 'var(--bg-surface)', borderBottom: '1px solid var(--border-subtle)', borderTop: customRoles.length > 0 ? '4px solid var(--bg-subtle)' : 'none', position: 'sticky', top: customRoles.length > 0 ? 0 : 0, zIndex: 10 }}>
                          <h3 className="db-section-label" style={{ margin: 0 }}>System roles</h3>
                        </div>
                        <div style={{ display: 'flex', flexDirection: 'column' }}>
                          {systemRoles.map(role => (
                            <motion.div key={role.id} variants={fadeUp}>
                              <RoleCard
                                role={role}
                                expanded={expandedId === role.id}
                                editState={editState?.roleId === role.id ? editState : null}
                                canEdit={canEditRole(role)}
                                onToggle={() => setExpandedId(expandedId === role.id ? null : role.id)}
                                onStartEdit={() => startEdit(role)}
                                {...roleCardProps}
                              />
                            </motion.div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </motion.div>
          )}
            </div>
          </motion.div>
        </motion.div>
      </div>
    </div>
  );
}
