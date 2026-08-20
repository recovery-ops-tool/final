import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, ShieldAlert } from 'lucide-react-native';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/theme/useTheme';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { fraudCasesApi, type FraudCaseResponse } from '@/api/fraudCasesApi';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { formatDate } from '@/utils/date';

export default function FraudCasesScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [cases, setCases] = useState<FraudCaseResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await fraudCasesApi.list({ orgId: user.organizationId || '', size: 100 });
      setCases(response.content ?? []);
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

  const filtered = cases.filter((c) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      c.caseNumber?.toLowerCase().includes(q) ||
      c.category?.toLowerCase().includes(q) ||
      c.status?.toLowerCase().includes(q)
    );
  });

  const getStatusTone = (status: string) => {
    switch (status) {
      case 'CONFIRMED':
        return 'error';
      case 'REJECTED':
      case 'CLOSED':
        return 'success';
      default:
        return 'warning';
    }
  };

  if (loading) return <LoadingView label="Loading fraud cases…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Fraud cases</Text>
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
            placeholder="Search fraud cases by ID/category…"
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
                  Case: #{item.caseNumber}
                </Text>
                <Badge tone={getStatusTone(item.status)} label={item.status.replace(/_/g, ' ')} />
              </View>

              <Text variant="body" color="primary">{item.category.replace(/_/g, ' ')}</Text>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 2 }}>
                <Text variant="caption" color="secondary">Amount: {formatCurrency(item.amountInvolved)}</Text>
                <Text variant="caption" color="tertiary">Reported: {formatDate(item.reportedAt)}</Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load cases" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={ShieldAlert}
                title="No fraud reports"
                message="Incidents flagged under safety directives will appear here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
