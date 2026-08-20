import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, Landmark } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';
import { Screen, Text, EmptyState, LoadingView, Card } from '@/components/ui';
import { reconciliationApi, type ReconciliationRunResponse } from '@/api/reconciliationApi';
import { formatDate } from '@/utils/date';

export default function ReconciliationScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [runs, setRuns] = useState<ReconciliationRunResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await reconciliationApi.listRuns({ orgId: user.organizationId || '', size: 100 });
      setRuns(response.content ?? []);
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

  const filtered = runs.filter((r) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return r.source?.toLowerCase().includes(q);
  });

  if (loading) return <LoadingView label="Loading reconciliation runs…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Reconciliation</Text>
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
            placeholder="Search runs by source statement…"
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
          renderItem={({ item }) => {
            const rate = item.rowsIngested > 0 ? `${((item.matched / item.rowsIngested) * 100).toFixed(0)}%` : '—';
            return (
              <Card style={{ padding: spacing.s4, gap: spacing.s2 }}>
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="bodyMedium" style={{ fontWeight: '700', color: colors.ink1 }}>
                    {item.source}
                  </Text>
                  <Text style={{ color: colors.success, fontWeight: '700', fontSize: 13 }}>{rate} Matched</Text>
                </View>

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text variant="caption" color="secondary">Rows: {item.rowsIngested} (Matched: {item.matched})</Text>
                  {item.exceptions > 0 ? (
                    <Text variant="caption" color="error">Exceptions: {item.exceptions}</Text>
                  ) : null}
                </View>

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                  <Text variant="caption" color="tertiary">As of: {formatDate(item.asOfDate)}</Text>
                  <Text variant="caption" color="tertiary">Run: {formatDate(item.createdAt)}</Text>
                </View>
              </Card>
            );
          }}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load runs" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={Landmark}
                title="No reconciliation runs"
                message="Bank statements matched against collections will list here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
