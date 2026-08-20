import React, { useCallback, useState } from 'react';
import {
  FlatList, View, TextInput, Pressable,
  Modal, TouchableWithoutFeedback, ScrollView, ActivityIndicator,
} from 'react-native';
import { useFocusEffect } from 'expo-router';
import {
  Search, WifiOff, X, Users, SquarePen,
  Lock, ToggleLeft, ToggleRight, Trash2, Plus, UserPlus,
} from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import {
  Screen, Text, EmptyState, LoadingView, Card, Badge,
  Button, TextField, ConfirmDialog,
} from '@/components/ui';
import { usersApi, type UserResponse } from '@/api/usersApi';
import { rolesApi, type RoleResponse } from '@/api/rolesApi';
import { useToast } from '@/context/ToastContext';
import { useAuth } from '@/context/AuthContext';
import type { UserPermissionsResponse } from '@/types/core';

// ─── helpers ─────────────────────────────────────────────────────────────────

const roleLabel = (name: string) =>
  name.replace(/^ROLE_/, '').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

// ─── Create Modal ────────────────────────────────────────────────────────────

interface CreateModalProps {
  roles: RoleResponse[];
  onClose: () => void;
  onCreated: () => void;
}

function CreateModal({ roles, onClose, onCreated }: CreateModalProps) {
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [selectedRoles, setSelectedRoles] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const toggleRole = (roleName: string) => {
    setSelectedRoles((prev) =>
      prev.includes(roleName) ? prev.filter((r) => r !== roleName) : [...prev, roleName],
    );
  };

  const submit = async () => {
    if (!firstName.trim()) { setError('First name is required'); return; }
    if (!lastName.trim()) { setError('Last name is required'); return; }
    if (!email.trim()) { setError('Email is required'); return; }
    setSubmitting(true);
    setError(null);
    try {
      await usersApi.createUser({
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim().toLowerCase(),
        roleNames: selectedRoles,
      });
      showToast('User created successfully', { type: 'success' });
      onCreated();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to create user');
    } finally {
      setSubmitting(false);
    }
  };

  const assignable = roles.filter((r) => r.name !== 'ROLE_PLATFORM_ADMIN');

  return (
    <Modal visible transparent animationType="slide">
      <TouchableWithoutFeedback onPress={onClose}>
        <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: colors.overlay }}>
          <TouchableWithoutFeedback>
            <View style={{
              backgroundColor: colors.surface,
              borderTopLeftRadius: radius.xl,
              borderTopRightRadius: radius.xl,
              padding: spacing.s5,
              gap: spacing.s4,
            }}>
              {/* Header */}
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                  <UserPlus size={16} color={colors.accent} />
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>New User</Text>
                </View>
                <Pressable onPress={onClose} hitSlop={8}>
                  <X size={20} color={colors.ink2} />
                </Pressable>
              </View>

              <Text variant="caption" color="secondary">
                Created in your organization. A welcome email with a one-time link will be sent.
              </Text>

              {/* Name row */}
              <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
                <View style={{ flex: 1 }}>
                  <TextField
                    label="First name"
                    required
                    value={firstName}
                    onChangeText={setFirstName}
                    placeholder="First name"
                    autoCapitalize="words"
                  />
                </View>
                <View style={{ flex: 1 }}>
                  <TextField
                    label="Last name"
                    required
                    value={lastName}
                    onChangeText={setLastName}
                    placeholder="Last name"
                    autoCapitalize="words"
                  />
                </View>
              </View>

              {/* Email */}
              <TextField
                label="Email"
                required
                value={email}
                onChangeText={setEmail}
                placeholder="someone@example.com"
                keyboardType="email-address"
                autoCapitalize="none"
              />

              {/* Roles */}
              {assignable.length > 0 ? (
                <View style={{ gap: spacing.s2 }}>
                  <Text variant="label" color="secondary">ROLES</Text>
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
                    {assignable.map((r) => {
                      const on = selectedRoles.includes(r.name);
                      return (
                        <Pressable
                          key={r.id}
                          onPress={() => toggleRole(r.name)}
                          style={{
                            paddingHorizontal: spacing.s3,
                            paddingVertical: spacing.s1 + 2,
                            borderRadius: radius.pill,
                            borderWidth: 1,
                            borderColor: on ? colors.accent : colors.border,
                            backgroundColor: on ? colors.accentSubtle : colors.surface,
                          }}
                        >
                          <Text
                            variant="caption"
                            style={{ color: on ? colors.accent : colors.ink2, fontWeight: on ? '600' : '400' }}
                          >
                            {roleLabel(r.name)}
                          </Text>
                        </Pressable>
                      );
                    })}
                  </View>
                </View>
              ) : null}

              {/* Error */}
              {error ? (
                <View style={{
                  backgroundColor: colors.errorSubtle,
                  borderRadius: radius.md,
                  padding: spacing.s3,
                  borderWidth: 1,
                  borderColor: colors.error,
                }}>
                  <Text variant="caption" color="error">{error}</Text>
                </View>
              ) : null}

              {/* Actions */}
              <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
                <Button label="Cancel" variant="outline" onPress={onClose} style={{ flex: 1 }} />
                <Button
                  label={submitting ? 'Creating…' : 'Create user'}
                  variant="primary"
                  onPress={submit}
                  disabled={submitting}
                  style={{ flex: 1 }}
                />
              </View>
            </View>
          </TouchableWithoutFeedback>
        </View>
      </TouchableWithoutFeedback>
    </Modal>
  );
}

// ─── Edit Modal ───────────────────────────────────────────────────────────────

interface EditModalProps {
  user: UserResponse;
  onClose: () => void;
  onSaved: () => void;
}

function EditModal({ user, onClose, onSaved }: EditModalProps) {
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [firstName, setFirstName] = useState(user.firstName ?? '');
  const [lastName, setLastName] = useState(user.lastName ?? '');
  const [email, setEmail] = useState(user.email ?? '');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!firstName.trim()) { setError('First name is required'); return; }
    if (!email.trim()) { setError('Email is required'); return; }
    setSubmitting(true);
    setError(null);
    try {
      await usersApi.updateUser(user.id, {
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim().toLowerCase(),
      });
      showToast('User updated successfully', { type: 'success' });
      onSaved();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to update user');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal visible transparent animationType="slide">
      <TouchableWithoutFeedback onPress={onClose}>
        <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: colors.overlay }}>
          <TouchableWithoutFeedback>
            <View style={{
              backgroundColor: colors.surface,
              borderTopLeftRadius: radius.xl,
              borderTopRightRadius: radius.xl,
              padding: spacing.s5,
              gap: spacing.s4,
            }}>
              {/* Header */}
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                  <SquarePen size={16} color={colors.ink2} />
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>Edit User</Text>
                </View>
                <Pressable onPress={onClose} hitSlop={8}>
                  <X size={20} color={colors.ink2} />
                </Pressable>
              </View>

              <Text variant="caption" color="secondary">{user.email}</Text>

              {/* Fields */}
              <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
                <View style={{ flex: 1 }}>
                  <TextField
                    label="First name"
                    required
                    value={firstName}
                    onChangeText={setFirstName}
                    placeholder="First name"
                    autoCapitalize="words"
                  />
                </View>
                <View style={{ flex: 1 }}>
                  <TextField
                    label="Last name"
                    value={lastName}
                    onChangeText={setLastName}
                    placeholder="Last name"
                    autoCapitalize="words"
                  />
                </View>
              </View>

              <TextField
                label="Email"
                required
                value={email}
                onChangeText={setEmail}
                placeholder="someone@example.com"
                keyboardType="email-address"
                autoCapitalize="none"
              />

              {error ? (
                <View style={{
                  backgroundColor: colors.errorSubtle,
                  borderRadius: radius.md,
                  padding: spacing.s3,
                  borderWidth: 1,
                  borderColor: colors.error,
                }}>
                  <Text variant="caption" color="error">{error}</Text>
                </View>
              ) : null}

              {/* Actions */}
              <View style={{ flexDirection: 'row', gap: spacing.s3 }}>
                <Button label="Cancel" variant="outline" onPress={onClose} style={{ flex: 1 }} />
                <Button
                  label={submitting ? 'Saving…' : 'Save changes'}
                  variant="primary"
                  onPress={submit}
                  disabled={submitting}
                  style={{ flex: 1 }}
                />
              </View>
            </View>
          </TouchableWithoutFeedback>
        </View>
      </TouchableWithoutFeedback>
    </Modal>
  );
}

// ─── Permissions Modal ────────────────────────────────────────────────────────

interface PermissionsModalProps {
  user: UserResponse;
  onClose: () => void;
}

function PermissionsModal({ user, onClose }: PermissionsModalProps) {
  const { colors, spacing, radius } = useTheme();
  const [perms, setPerms] = useState<UserPermissionsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useCallback(() => {
    usersApi.getUserPermissions(user.id)
      .then((p) => { setPerms(p); setError(false); })
      .catch(() => setError(true))
      .finally(() => setLoading(false));
  }, [user.id])();

  const allPerms = [
    ...(perms?.fromRoles ?? []),
    ...(perms?.direct ?? []),
  ];

  return (
    <Modal visible transparent animationType="slide">
      <TouchableWithoutFeedback onPress={onClose}>
        <View style={{ flex: 1, justifyContent: 'flex-end', backgroundColor: colors.overlay }}>
          <TouchableWithoutFeedback>
            <View style={{
              backgroundColor: colors.surface,
              borderTopLeftRadius: radius.xl,
              borderTopRightRadius: radius.xl,
              padding: spacing.s5,
              maxHeight: '70%',
              gap: spacing.s4,
            }}>
              {/* Header */}
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                  <Lock size={16} color={colors.ink2} />
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>Access & Permissions</Text>
                </View>
                <Pressable onPress={onClose} hitSlop={8}>
                  <X size={20} color={colors.ink2} />
                </Pressable>
              </View>

              <Text variant="caption" color="secondary">{user.firstName} {user.lastName} · {user.email}</Text>

              {loading ? (
                <ActivityIndicator color={colors.accent} style={{ paddingVertical: spacing.s5 }} />
              ) : error ? (
                <Text variant="caption" color="error" style={{ textAlign: 'center', paddingVertical: spacing.s4 }}>
                  Failed to load permissions.
                </Text>
              ) : (
                <ScrollView showsVerticalScrollIndicator={false} style={{ flex: 1 }}>
                  {/* Roles */}
                  <Text variant="label" color="secondary" style={{ marginBottom: spacing.s2 }}>ROLES</Text>
                  {(user.roles ?? []).length === 0 ? (
                    <Text variant="caption" color="tertiary">No roles assigned</Text>
                  ) : (
                    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: spacing.s4 }}>
                      {(user.roles ?? []).map((r) => (
                        <Badge key={r.name} tone="info" label={roleLabel(r.name)} />
                      ))}
                    </View>
                  )}

                  {/* Permissions */}
                  <Text variant="label" color="secondary" style={{ marginBottom: spacing.s2 }}>PERMISSIONS</Text>
                  {allPerms.length === 0 ? (
                    <Text variant="caption" color="tertiary">No explicit permissions</Text>
                  ) : (
                    <View style={{ gap: spacing.s2 }}>
                      {allPerms.map((p, i) => (
                        <View key={i} style={{
                          flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
                          paddingVertical: spacing.s2, borderBottomWidth: 1, borderBottomColor: colors.border,
                        }}>
                          <View>
                            <Text variant="bodyMedium" style={{ color: colors.ink1 }}>{p.name}</Text>
                            {p.resource ? (
                              <Text variant="caption" color="tertiary">{p.resource} · {p.action}</Text>
                            ) : null}
                          </View>
                          <Badge tone="success" label="Granted" />
                        </View>
                      ))}
                    </View>
                  )}
                </ScrollView>
              )}

              <Button label="Close" variant="outline" onPress={onClose} />
            </View>
          </TouchableWithoutFeedback>
        </View>
      </TouchableWithoutFeedback>
    </Modal>
  );
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

export default function UsersScreen() {
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();
  const { role, user } = useAuth();

  const canManage = role === 'ORG_ADMIN' || role === 'PLATFORM_ADMIN' || role === 'AGENCY_ADMIN' || role === 'BANK_ADMIN' || role === 'TL' || role === 'MANAGER';
  const canDelete = role === 'ORG_ADMIN' || role === 'PLATFORM_ADMIN' || role === 'TL' || role === 'MANAGER';

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);
  const [assignableRoles, setAssignableRoles] = useState<RoleResponse[]>([]);

  // Modal state
  const [showCreate, setShowCreate] = useState(false);
  const [editUser, setEditUser] = useState<UserResponse | null>(null);
  const [permUser, setPermUser] = useState<UserResponse | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<UserResponse | null>(null);
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  // ── load ──────────────────────────────────────────────────────────────────

  const load = useCallback(async () => {
    try {
      const response = await usersApi.listUsers(0, 100);
      setUsers(response.content ?? []);
      setLoadError(false);
    } catch (e: any) {
      if (e?.response?.status === 403 && user) {
        // Fallback for Field Officers testing the app who lack ORG_ADMIN roles
        setUsers([user]);
        setLoadError(false);
      } else {
        setLoadError(true);
      }
    }
  }, [user]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
      rolesApi.listRoles()
        .then((r) => setAssignableRoles(r))
        .catch(() => {});
    }, [load]),
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  // ── actions ───────────────────────────────────────────────────────────────

  const handleToggleEnabled = async (u: UserResponse) => {
    setTogglingId(u.id);
    try {
      if (u.enabled) {
        await usersApi.disableUser(u.id);
        showToast(`${u.firstName} disabled`, { type: 'warning' });
      } else {
        await usersApi.enableUser(u.id);
        showToast(`${u.firstName} enabled`, { type: 'success' });
      }
      await load();
    } catch (e: any) {
      showToast(e?.response?.data?.message || 'Failed to update user', { type: 'error' });
    } finally {
      setTogglingId(null);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeletingId(deleteTarget.id);
    setDeleteTarget(null);
    try {
      await usersApi.deleteUser(deleteTarget.id);
      showToast('User deleted', { type: 'success' });
      await load();
    } catch (e: any) {
      showToast(e?.response?.data?.message || 'Failed to delete user', { type: 'error' });
    } finally {
      setDeletingId(null);
    }
  };

  // ── filter ────────────────────────────────────────────────────────────────

  const filtered = users.filter((u) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    const fullName = `${u.firstName} ${u.lastName}`.toLowerCase();
    return u.email?.toLowerCase().includes(q) || fullName.includes(q);
  });

  if (loading) return <LoadingView label="Loading user directory..." />;

  // ── render ────────────────────────────────────────────────────────────────

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>

        {/* Header */}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Users setup</Text>
        </View>
          {canManage ? (
            <Pressable
              onPress={() => setShowCreate(true)}
              style={({ pressed }) => ({
                flexDirection: 'row',
                alignItems: 'center',
                gap: 4,
                paddingHorizontal: spacing.s3,
                paddingVertical: spacing.s2,
                borderRadius: radius.md,
                backgroundColor: pressed ? colors.accent + 'cc' : colors.accent,
              })}
            >
              <Plus size={15} color="#fff" />
              <Text style={{ color: '#fff', fontWeight: '600', fontSize: 13, fontFamily: 'Inter_600SemiBold' }}>
                New user
              </Text>
            </Pressable>
          ) : null}
        </View>

        {/* Search */}
        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md,
          paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}>
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search users by name or email..."
            placeholderTextColor={colors.ink3}
            style={{
              flex: 1, paddingVertical: spacing.s3,
              color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15,
            }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        {/* List */}
        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => {
            const fullName = `${item.firstName ?? ''} ${item.lastName ?? ''}`.trim() || '—';
            const initials = `${item.firstName?.[0] ?? ''}${item.lastName?.[0] ?? ''}`.toUpperCase() || '?';
            const isToggling = togglingId === item.id;
            const isDeleting = deletingId === item.id;

            return (
              <Card style={{ padding: spacing.s4, gap: spacing.s5 }}>
                {/* Top row: avatar + name + status badge */}
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s3 }}>
                  {/* Initials avatar */}
                  <View style={{
                    width: 36, height: 36, borderRadius: radius.md,
                    backgroundColor: colors.accentSubtle,
                    borderWidth: 1, borderColor: colors.accent + '40',
                    alignItems: 'center', justifyContent: 'center',
                  }}>
                    <Text style={{ fontSize: 13, fontWeight: '700', color: colors.accent }}>
                      {initials}
                    </Text>
                  </View>

                  <View style={{ flex: 1 }}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s2 }}>
                      <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                        {fullName}
                      </Text>
                      <Badge tone={item.enabled ? 'success' : 'neutral'} label={item.enabled ? 'Active' : 'Disabled'} />
                    </View>
                    <Text variant="caption" color="secondary">{item.email}</Text>
                  </View>
                </View>

                {/* Role badges + action icons on same row */}
                <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                  {/* Badges (left) */}
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 4, flex: 1 }}>
                    {item.roles && item.roles.length > 0 ? (
                      item.roles.map((r) => (
                        <Badge key={r.name} tone="info" label={roleLabel(r.name)} />
                      ))
                    ) : (
                      <Text variant="caption" color="tertiary" style={{ fontStyle: 'italic' }}>No roles assigned</Text>
                    )}
                  </View>

                  {/* Action icons (right) */}
                  {canManage ? (
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.s1 }}>
                      {/* Edit */}
                      <Pressable
                        onPress={() => setEditUser(item)}
                        style={({ pressed }) => ({
                          padding: spacing.s2,
                          borderRadius: radius.sm,
                          backgroundColor: pressed ? colors.subtle : 'transparent',
                        })}
                        hitSlop={6}
                      >
                        <SquarePen size={17} color={colors.ink2} />
                      </Pressable>

                      {/* Access / Permissions */}
                      <Pressable
                        onPress={() => setPermUser(item)}
                        style={({ pressed }) => ({
                          padding: spacing.s2,
                          borderRadius: radius.sm,
                          backgroundColor: pressed ? colors.subtle : 'transparent',
                        })}
                        hitSlop={6}
                      >
                        <Lock size={17} color={colors.ink2} />
                      </Pressable>

                      {/* Enable / Disable toggle */}
                      <Pressable
                        onPress={() => handleToggleEnabled(item)}
                        disabled={isToggling}
                        style={({ pressed }) => ({
                          padding: spacing.s2,
                          borderRadius: radius.sm,
                          backgroundColor: pressed ? colors.subtle : 'transparent',
                          opacity: isToggling ? 0.4 : 1,
                        })}
                        hitSlop={6}
                      >
                        {item.enabled
                          ? <ToggleRight size={19} color={colors.success} />
                          : <ToggleLeft size={19} color={colors.ink3} />}
                      </Pressable>

                      {/* Delete */}
                      {canDelete ? (
                        <Pressable
                          onPress={() => setDeleteTarget(item)}
                          disabled={isDeleting}
                          style={({ pressed }) => ({
                            padding: spacing.s2,
                            borderRadius: radius.sm,
                            backgroundColor: pressed ? colors.errorSubtle : 'transparent',
                            opacity: isDeleting ? 0.4 : 1,
                          })}
                          hitSlop={6}
                        >
                          <Trash2 size={17} color={colors.error} />
                        </Pressable>
                      ) : null}
                    </View>
                  ) : null}
                </View>
              </Card>
            );
          }}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load users" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={Users}
                title="No users found"
                message="Organization team members will be listed here."
              />
            )
          }
        />
      </View>

      {/* Create Modal */}
      {showCreate ? (
        <CreateModal
          roles={assignableRoles}
          onClose={() => setShowCreate(false)}
          onCreated={() => { setShowCreate(false); load(); }}
        />
      ) : null}

      {/* Edit Modal */}
      {editUser ? (
        <EditModal
          user={editUser}
          onClose={() => setEditUser(null)}
          onSaved={() => { setEditUser(null); load(); }}
        />
      ) : null}

      {/* Permissions Modal */}
      {permUser ? (
        <PermissionsModal
          user={permUser}
          onClose={() => setPermUser(null)}
        />
      ) : null}

      {/* Delete Confirm Dialog */}
      <ConfirmDialog
        visible={!!deleteTarget}
        title="Delete user?"
        message={`This will permanently delete ${deleteTarget?.email ?? 'this user'}. This cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        isDestructive
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </Screen>
  );
}
