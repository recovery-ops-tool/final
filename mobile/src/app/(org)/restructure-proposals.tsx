import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, Layers } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { restructureProposalsApi, type RestructureProposalResponse } from '@/api/restructureProposalsApi';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { formatDate } from '@/utils/date';

export default function RestructureProposalsScreen() {
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [proposals, setProposals] = useState<RestructureProposalResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    try {
      const response = await restructureProposalsApi.list({ size: 100 });
      setProposals(response.content ?? []);
      setLoadError(false);
    } catch (e) {
      setLoadError(true);
    }
  }, []);

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

  const filtered = proposals.filter((p) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return p.allocationId?.toLowerCase().includes(q) || p.status?.toLowerCase().includes(q);
  });

  const getStatusTone = (status: string) => {
    switch (status) {
      case 'APPROVED':
      case 'ACCEPTED':
      case 'ACTIVE':
        return 'success';
      case 'REJECTED':
        return 'error';
      default:
        return 'warning';
    }
  };

  if (loading) return <LoadingView label="Loading proposals…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Restructures</Text>
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
            placeholder="Search proposals by case ID…"
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
                  Case ID: {item.allocationId.slice(-8).toUpperCase()}
                </Text>
                <Badge tone={getStatusTone(item.status)} label={item.status.replace(/_/g, ' ')} />
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="caption" color="secondary">
                  EMI: {formatCurrency(item.originalEmiAmount)} → {formatCurrency(item.newEmiAmount)}
                </Text>
                <Text variant="caption" color="secondary">Count: {item.newEmiCount}</Text>
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                <Text variant="caption" color="tertiary">Drafted: {formatDate(item.createdAt)}</Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load proposals" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={Layers}
                title="No proposals"
                message="Restructure EMI renegotiations will show up here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
