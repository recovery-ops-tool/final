import React, { useCallback, useState } from 'react';
import { FlatList, View, StyleSheet, TextInput, Pressable } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Search, WifiOff, X, ShieldAlert } from 'lucide-react-native';
import { useTheme } from '@/theme/useTheme';
import { useAuth } from '@/context/AuthContext';
import { Screen, Text, EmptyState, LoadingView, Card, Badge } from '@/components/ui';
import { npaApi, type RiskRecordResponse } from '@/api/npaApi';
import { formatCurrency } from '@/utils/allocationHeuristics';
import { formatDate } from '@/utils/date';

export default function PortfolioRiskScreen() {
  const { user } = useAuth();
  const { colors, spacing, radius } = useTheme();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [records, setRecords] = useState<RiskRecordResponse[]>([]);
  const [search, setSearch] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async () => {
    if (!user) return;
    try {
      const response = await npaApi.getRecords(user.organizationId || '', undefined, 0, 100);
      setRecords(response.content ?? []);
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

  const filtered = records.filter((r) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      r.loanNumber?.toLowerCase().includes(q) ||
      r.borrowerName?.toLowerCase().includes(q) ||
      r.riskLevel?.toLowerCase().includes(q)
    );
  });

  const getStatusTone = (lvl: string) => {
    switch (lvl) {
      case 'CRITICAL':
      case 'HIGH':
        return 'error';
      case 'MEDIUM':
        return 'warning';
      default:
        return 'neutral';
    }
  };

  if (loading) return <LoadingView label="Loading portfolio risk details…" />;

  return (
    <Screen scroll={false} padded={false} edges={['top']}>
      <View style={{ paddingHorizontal: spacing.s4, paddingTop: spacing.s2, gap: spacing.s4 }}>
        <View style={{ marginTop: -8 }}>
          <Text style={{ fontSize: 13, fontWeight: '400', color: colors.ink3, fontFamily: 'Inter_400Regular' }}>Portfolio risk exposure</Text>
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
            placeholder="Search risk by borrower or ID…"
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
                  {item.borrowerName || 'Unknown Borrower'}
                </Text>
                <Badge tone={getStatusTone(item.riskLevel)} label={item.riskLevel} />
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text variant="caption" color="secondary">Loan: {item.loanNumber}</Text>
                <Text variant="bodyMedium" style={{ fontWeight: '600', color: colors.accent }}>
                  {formatCurrency(item.outstandingAmount)}
                </Text>
              </View>

              <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 }}>
                <Text variant="caption" color="tertiary">Overdue: {item.overdueDays} days</Text>
                <Text variant="caption" color="tertiary">Last Pay: {item.lastPaymentDate ? formatDate(item.lastPaymentDate) : '—'}</Text>
              </View>
            </Card>
          )}
          refreshing={refreshing}
          onRefresh={onRefresh}
          ListEmptyComponent={
            loadError ? (
              <EmptyState icon={WifiOff} title="Couldn't load risk items" message="Pull down to try again." />
            ) : (
              <EmptyState
                icon={ShieldAlert}
                title="No exposure flags"
                message="Accounts matching risk profiles will appear here."
              />
            )
          }
        />
      </View>
    </Screen>
  );
}
