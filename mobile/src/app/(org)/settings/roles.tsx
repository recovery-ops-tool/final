import React, { useCallback, useState } from 'react';
import {
  FlatList, View, TextInput, Pressable,
  Modal, TouchableWithoutFeedback,
} from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, ShieldAlert, Plus, ShieldCheck } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import {
  Screen, Text, EmptyState, LoadingView, Card, Badge, Button, TextField,
} from '@/components/ui';
import { rolesApi, type RoleResponse } from '@/api/rolesApi';
import { useToast } from '@/context/ToastContext';
import { useAuth } from '@/context/AuthContext';

// ─── Create Role Modal ────────────────────────────────────────────────────────

interface CreateRoleModalProps {
  onClose: () => void;
  onCreated: (role: RoleResponse) => void;
}

function CreateRoleModal({ onClose, onCreated }: CreateRoleModalProps) {
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!name.trim()) { setError('Role name is required'); return; }
    setSubmitting(true);
    setError(null);
    try {
      const created = await rolesApi.createRole({
        name: name.trim().toUpperCase().replace(/\s+/g, '_'),
        description: description.trim() || undefined,
      });
      showToast('Role created successfully', { type: 'success' });
      onCreated(created);
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Failed to create role');
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
                  <ShieldCheck size={16} color={colors.accent} />
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>New Role</Text>
                </View>
                <Pressable onPress={onClose} hitSlop={8}>
                  <X size={20} color={colors.ink2} />
                </Pressable>
              </View>

              <Text variant="caption" color="secondary">
                Create a custom role for your organization. Role name will be uppercased automatically.
              </Text>

              {/* Role name */}
              <TextField
                label="Role name"
                required
                value={name}
                onChangeText={setName}
                placeholder="e.g. TEAM_LEAD"
                autoCapitalize="characters"
              />

              {/* Description */}
              <TextField
                label="Description"
                value={description}
                onChangeText={setDescription}
                placeholder="Optional description"
              />

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
                  label={submitting ? 'Creating…' : 'Create role'}
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

// ─── Main Screen ──────────────────────────────────────────────────────────────

export default function RoleManagementScreen() {
  const { colors, spacing, radius } = useTheme();
  const { role } = useAuth();

  const canCreate = role === 'ORG_ADMIN' || role === 'PLATFORM_ADMIN' || role === 'TL' || role === 'MANAGER';

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);
  const [showCreate, setShowCreate] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await rolesApi.listRoles();
      setRoles(response ?? []);
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load]),
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const filtered = roles.filter((r) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      r.name?.toLowerCase().includes(q) ||
      r.description?.toLowerCase().includes(q)
    );
  });

  if (loading) return <LoadingView label="Loading access control roles..." />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>

        {/* Header */}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Role management</Text>
        </View>
          {canCreate ? (
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
                New role
              </Text>
            </Pressable>
          ) : null}
        </View>

        {/* Search */}
        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}>
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search roles..."
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
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
          renderItem={({ item }) => (
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                  {item.name.replace('ROLE_', '')}
                </Text>
                <Badge tone={item.systemRole ? 'info' : 'accent'} label={item.systemRole ? 'System' : 'Custom'} />
              </View>
              {item.description ? <Text variant="body" color="primary">{item.description}</Text> : null}
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load roles" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={ShieldAlert}
                title="No roles found"
                message="Roles mapped under customization policies will list here."
              />
            )
          }
        />
      </View>

      {/* Create Role Modal */}
      {showCreate ? (
        <CreateRoleModal
          onClose={() => setShowCreate(false)}
          onCreated={(newRole) => {
            setRoles((prev) => [...prev, newRole]);
            setShowCreate(false);
          }}
        />
      ) : null}
    </Screen>
  );
}
