import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, AlertTriangle } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';
import { Screen, Text, EmptyState, LoadingView, Card, Badge, Button } from '@/components/ui';
import { fieldOpsApi, type IncidentReportResponse } from '@/api/fieldOpsApi';
import { useToast } from '@/context/ToastContext';
import { formatDate } from '@/utils/date';

export default function FieldOpsScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();
  const { showToast } = useToast();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [incidents, setIncidents] = useState<IncidentReportResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);
  const [resolvingId, setResolvingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await fieldOpsApi.listIncidents({ orgId: user.organizationId || '', unresolvedOnly: true, size: 100 });
      setIncidents(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, [user]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      load().finally(() => setLoading(false));
    }, [load])
  );

  const onRefresh = async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  };

  const handleResolve = async (id: string) => {
    setResolvingId(id);
    try {
      await fieldOpsApi.resolveIncident(id, 'Resolved via mobile field ops panel.');
      showToast('Incident marked resolved successfully', { type: 'success' });
      load();
    } catch (e) {
      showToast('Failed to resolve incident', { type: 'error' });
    } finally {
      setResolvingId(null);
    }
  };

  const filtered = incidents.filter((i) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      i.agentName?.toLowerCase().includes(q) ||
      i.type?.toLowerCase().includes(q) ||
      i.description?.toLowerCase().includes(q)
    );
  });

  if (loading) return <LoadingView label="Loading active field incidents…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Field operations</Text>
        </View>

        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: spacing.s2,
          backgroundColor: colors.subtle, borderRadius: radius.md, paddingHorizontal: spacing.s3,
          borderWidth: 1, borderColor: colors.border, marginBottom: spacing.s2
        }}
        >
          <Search size={16} color={colors.ink3} />
          <TextInput
            value={search}
            onChangeText={setSearch}
            placeholder="Search active alerts…"
            placeholderTextColor={colors.ink3}
            style={{ flex: 1, paddingVertical: spacing.s3, color: colors.ink1, fontFamily: 'Inter_400Regular', fontSize: 15 }}
          />
          {search.length > 0 ? (
            <Pressable onPress={() => setSearch('')} hitSlop={8}>
              <X size={16} color={colors.ink3} />
            </Pressable>
          ) : null}
        </View>

        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={{ paddingHorizontal: spacing.s4, paddingBottom: spacing.s8, gap: spacing.s3 }}
          renderItem={({ item }) => (
            <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                  {item.agentName || 'Unknown Agent'}
                </Text>
                <Badge tone="error" label={item.type || 'SOS'} />
              </View>

              <Text variant="body" color="primary">{item.description}</Text>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 4 }}>
                <Text variant="caption" color="tertiary">Triggered: {formatDate(item.createdAt)}</Text>
                <Button
                  label={resolvingId === item.id ? 'Resolving…' : 'Resolve'}
                  variant="outline"
                  onPress={() => handleResolve(item.id)}
                  disabled={resolvingId !== null}
                  fullWidth={false}
                />
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load alerts" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={AlertTriangle}
                title="Field clear"
                message="No active SOS or safety incidents reported right now."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
